package com.tickethub.payment.service;

import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.client.BookingDto;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.PaymentStatus;
import com.tickethub.payment.dto.CreatePaymentResult;
import com.tickethub.payment.dto.PaymentResponse;
import com.tickethub.payment.exception.ApiException;
import com.tickethub.payment.metrics.PaymentMetrics;
import com.tickethub.payment.provider.ChargeOutcome;
import com.tickethub.payment.provider.PaymentProviderGateway;
import com.tickethub.payment.provider.ProviderCallTimeoutException;
import com.tickethub.payment.provider.ProviderUnavailableException;
import com.tickethub.payment.provider.SimulationHints;
import com.tickethub.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment flow (ST-20..ST-24, ST-54, ST-55). No @Transactional around the whole thing on purpose:
 * we must never hold a database transaction open while waiting for a slow provider or another service.
 * Each save is its own short transaction.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final BookingClient bookingClient;
    private final PaymentProviderGateway gateway;
    private final Clock clock;
    private final PaymentMetrics metrics;

    public PaymentService(PaymentRepository paymentRepository, BookingClient bookingClient,
                          PaymentProviderGateway gateway, Clock clock, PaymentMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.bookingClient = bookingClient;
        this.gateway = gateway;
        this.clock = clock;
        this.metrics = metrics;
    }

    public CreatePaymentResult pay(String customerId, Long bookingId, SimulationHints hints) {
        // 1. Idempotency: a booking is paid for at most once.
        Payment existing = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (existing != null) {
            assertOwner(existing, customerId);
            return new CreatePaymentResult(PaymentResponse.from(existing), false);
        }

        // 2. The Booking Service is the source of truth for amount, owner and state.
        BookingDto booking = bookingClient.getBooking(bookingId);
        if (!customerId.equals(booking.customerId())) {
            throw ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + bookingId + " not found.");
        }
        if (!"PENDING_PAYMENT".equals(booking.status())) {
            throw ApiException.conflict("BOOKING_NOT_PAYABLE",
                    "Booking is " + booking.status() + " and cannot be paid.");
        }
        if (booking.expiryAt() != null && !booking.expiryAt().isAfter(clock.instant())) {
            throw ApiException.conflict("BOOKING_EXPIRED", "The reservation has expired. Please book again.");
        }

        // 2b. Circuit breaker: if the provider has been failing, answer 503 immediately, before anything is recorded.
        if (!gateway.isAvailable()) {
            throw providerUnavailable();
        }

        // 3. Record the attempt FIRST (the unique constraint settles any race between two requests).
        String reference = "sim_" + UUID.randomUUID();
        Payment payment;
        try {
            payment = paymentRepository.saveAndFlush(new Payment(bookingId, customerId, booking.amount(),
                    booking.currency(), gateway.providerName(), reference, clock.instant()));
        } catch (DataIntegrityViolationException race) {
            Payment winner = paymentRepository.findByBookingId(bookingId).orElseThrow(() -> race);
            assertOwner(winner, customerId);
            return new CreatePaymentResult(PaymentResponse.from(winner), false);
        }
        MDC.put("paymentId", String.valueOf(payment.getId()));
        MDC.put("bookingId", String.valueOf(bookingId));
        try {
            log.info("PAYMENT_INITIATED bookingId={} paymentId={} amount={} {}", bookingId, payment.getId(),
                    booking.amount(), booking.currency());

            // 4. Call the provider with a hard timeout.
            ChargeOutcome outcome;
            try {
                outcome = gateway.charge(reference, booking.amount(), booking.currency(), hints);
            } catch (ProviderUnavailableException open) {
                // breaker opened between the check and the call: nothing was charged, so forget the attempt
                paymentRepository.delete(payment);
                throw providerUnavailable();
            } catch (ProviderCallTimeoutException timeout) {
                payment.moveTo(PaymentStatus.PENDING, "PROVIDER_TIMEOUT", clock.instant());
                paymentRepository.save(payment);
                metrics.payment("PENDING");
                log.warn("PAYMENT_PENDING paymentId={} (provider timeout, waiting for webhook)", payment.getId());
                throw ApiException.gatewayTimeout("PAYMENT_PROVIDER_TIMEOUT",
                        "The payment provider did not answer in time. Your payment is PENDING (id "
                                + payment.getId() + "); check its status shortly. You have not been charged twice.");
            }

            // 5. Final status, then tell Booking.
            if (outcome.success()) {
                payment.moveTo(PaymentStatus.SUCCESS, null, clock.instant());
            } else {
                payment.moveTo(PaymentStatus.FAILED, outcome.failureReason(), clock.instant());
            }
            paymentRepository.save(payment);
            metrics.payment(payment.getStatus().name());
            log.info("PAYMENT_{} paymentId={} bookingId={}", payment.getStatus(), payment.getId(), bookingId);
            syncToBooking(payment);
            return new CreatePaymentResult(PaymentResponse.from(payment), true);
        } finally {
            MDC.remove("paymentId");
            MDC.remove("bookingId");
        }
    }

    public PaymentResponse getPayment(Long paymentId, String customerId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found."));
        assertOwner(payment, customerId);
        return PaymentResponse.from(payment);
    }

    /** Reports a final payment to Booking. If Booking is down, booking_synced stays false and the scheduler retries. */
    public void syncToBooking(Payment payment) {
        if (!payment.getStatus().isFinal() || payment.isBookingSynced()) {
            return;
        }
        try {
            bookingClient.reportResult(payment.getBookingId(), payment.getStatus().name(), payment.getId());
            payment.markSynced(clock.instant());
            paymentRepository.save(payment);
        } catch (RuntimeException e) {
            log.warn("PAYMENT_SYNC_PENDING paymentId={} bookingId={}: {}", payment.getId(), payment.getBookingId(),
                    e.getMessage());
        }
    }

    /** Called by the scheduler: retry every final payment whose result Booking has not received yet. */
    public int retryUnsyncedPayments() {
        List<Payment> unsynced = paymentRepository.findByBookingSyncedFalseAndStatusIn(
                List.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED), PageRequest.of(0, 50));
        unsynced.forEach(this::syncToBooking);
        return (int) unsynced.stream().filter(Payment::isBookingSynced).count();
    }

    private ApiException providerUnavailable() {
        metrics.providerUnavailable();
        return ApiException.unavailable("PAYMENT_PROVIDER_UNAVAILABLE",
                "The payment provider is temporarily unavailable. You have not been charged; please try again shortly.");
    }

    private void assertOwner(Payment payment, String customerId) {
        if (!payment.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found.");
        }
    }

    Instant now() {
        return clock.instant();
    }
}
