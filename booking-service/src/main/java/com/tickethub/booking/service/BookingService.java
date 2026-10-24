package com.tickethub.booking.service;

import com.tickethub.booking.client.EventShowClient;
import com.tickethub.booking.client.ShowDto;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.BookingStateMachine;
import com.tickethub.booking.domain.BookingStatus;
import com.tickethub.booking.dto.BookingResponse;
import com.tickethub.booking.dto.CreateBookingRequest;
import com.tickethub.booking.dto.CreateBookingResult;
import com.tickethub.booking.dto.PaymentOutcome;
import com.tickethub.booking.event.BookingConfirmedEvent;
import com.tickethub.booking.event.BookingEventPublisher;
import com.tickethub.booking.exception.ApiException;
import com.tickethub.booking.metrics.BookingMetrics;
import org.slf4j.MDC;
import com.tickethub.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Booking use cases. Why TransactionTemplate instead of @Transactional on the whole method?
 * createBooking calls Event/Show over HTTP. A database transaction must NOT stay open (holding a connection)
 * while we wait for another service. So: remote calls first, THEN one short transaction for the writes.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final String UQ_IDEMPOTENCY = "uq_booking_customer_idempotency";
    private static final String UQ_ACTIVE_SEAT = "uq_booking_seat_active";

    private final BookingRepository bookingRepository;
    private final SeatReservationService seatReservationService;
    private final EventShowClient eventShowClient;
    private final BookingEventPublisher eventPublisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BookingMetrics metrics;
    private final long reservationMinutes;
    private final int maxSeatsPerBooking;

    public BookingService(BookingRepository bookingRepository,
                          SeatReservationService seatReservationService,
                          EventShowClient eventShowClient,
                          BookingEventPublisher eventPublisher,
                          TransactionTemplate tx,
                          Clock clock,
                          BookingMetrics metrics,
                          @Value("${booking.reservation-minutes}") long reservationMinutes,
                          @Value("${booking.max-seats-per-booking}") int maxSeatsPerBooking) {
        this.bookingRepository = bookingRepository;
        this.seatReservationService = seatReservationService;
        this.eventShowClient = eventShowClient;
        this.eventPublisher = eventPublisher;
        this.tx = tx;
        this.clock = clock;
        this.metrics = metrics;
        this.reservationMinutes = reservationMinutes;
        this.maxSeatsPerBooking = maxSeatsPerBooking;
    }

    // ------------------------------------------------------------------ create (ST-06, ST-07, ST-08, ST-53)

    public CreateBookingResult createBooking(String customerId, String customerEmail, String idempotencyKey,
                                             CreateBookingRequest request) {
        requireCustomerId(customerId);
        String recipient = customerEmail == null || customerEmail.isBlank() ? customerId + "@tickethub.local" : customerEmail;
        requireIdempotencyKey(idempotencyKey);
        List<Long> seatIds = request.seatIds().stream().sorted().toList();
        validateSeatList(seatIds);
        String fingerprint = fingerprint(request.showId(), seatIds);

        // 1. Retry of an earlier request? Return the ORIGINAL booking (SC-13).
        Optional<Booking> earlier = bookingRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), fingerprint);
        }

        // 2. Ask Event/Show (no database transaction is open here).
        ShowDto show = eventShowClient.getShow(request.showId());
        ensureBookable(show);
        ensureSeatsBelongToShow(request.showId(), seatIds);

        // 3. Friendly early check. NOT the guard: two requests can both pass it. The database index is the guard.
        List<Long> alreadyHeld = seatReservationService.findHeldSeatIds(request.showId(), seatIds);
        if (!alreadyHeld.isEmpty()) {
            metrics.seatConflict();
            throw seatUnavailable(alreadyHeld);
        }

        // 4. Price snapshot (ST-46) and expiry.
        BigDecimal amount = show.price().multiply(BigDecimal.valueOf(seatIds.size()));
        Instant now = Instant.now(clock);
        Instant expiryAt = now.plus(Duration.ofMinutes(reservationMinutes));

        // 5. One short transaction: booking row + one RESERVED row per seat. All or nothing.
        try {
            Booking saved = tx.execute(status -> {
                Booking booking = bookingRepository.saveAndFlush(new Booking(customerId, recipient, request.showId(),
                        amount, show.currency(), now, expiryAt, idempotencyKey, fingerprint));
                seatReservationService.reserveSeats(booking, seatIds);
                return booking;
            });
            metrics.created();
            log.info("BOOKING_CREATED bookingId={} customerId={} showId={} seats={} amount={}", saved.getId(),
                    customerId, request.showId(), seatIds, amount);
            return new CreateBookingResult(BookingResponse.from(saved, seatIds), true);
        } catch (DataIntegrityViolationException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains(UQ_ACTIVE_SEAT)) {
                // Lost the race: someone else's transaction committed this seat first. Ours rolled back completely.
                log.info("Seat conflict for show {} seats {} (customer {})", request.showId(), seatIds, customerId);
                metrics.seatConflict();
                throw seatUnavailable(seatIds);
            }
            if (detail.contains(UQ_IDEMPOTENCY)) {
                // Two identical retries raced each other; the other one won. Return its booking.
                Booking winner = bookingRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                        .orElseThrow(() -> e);
                return replay(winner, fingerprint);
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ view / cancel (ST-52)

    public BookingResponse getBooking(Long id, String customerId) {
        requireCustomerId(customerId);
        Booking booking = findBooking(id);
        requireOwner(booking, customerId);
        return toResponse(booking);
    }

    public BookingResponse cancelBooking(Long id, String customerId) {
        requireCustomerId(customerId);
        Booking cancelled = tx.execute(status -> {
            Booking booking = lockBooking(id);
            requireOwner(booking, customerId);
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                throw ApiException.conflict("CANCELLATION_NOT_ALLOWED",
                        "A confirmed booking cannot be cancelled in this version (refunds are Phase 2).");
            }
            BookingStateMachine.assertTransition(booking.getStatus(), BookingStatus.CANCELLED);
            booking.changeStatus(BookingStatus.CANCELLED, Instant.now(clock));
            seatReservationService.releaseSeats(booking.getId());
            return booking;
        });
        metrics.cancelled();
        log.info("BOOKING_CANCELLED bookingId={}", id);
        return toResponse(cancelled);
    }

    // ------------------------------------------------------------------ expiry (ST-09)

    /** Marks overdue PENDING_PAYMENT bookings EXPIRED and releases their seats. Returns how many were expired. */
    public int expireOverdueBookings() {
        List<Long> overdue = bookingRepository.findIdsByStatusAndExpiryAtBefore(
                BookingStatus.PENDING_PAYMENT, Instant.now(clock));
        int expired = 0;
        for (Long id : overdue) {
            try {
                if (expireOne(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Could not expire booking {}", id, e);
            }
        }
        return expired;
    }

    private boolean expireOne(Long id) {
        Boolean result = tx.execute(status -> {
            Booking booking = lockBooking(id);   // re-check under lock: it may have been paid/cancelled meanwhile
            if (booking.getStatus() != BookingStatus.PENDING_PAYMENT
                    || booking.getExpiryAt().isAfter(Instant.now(clock))) {
                return false;
            }
            booking.changeStatus(BookingStatus.EXPIRED, Instant.now(clock));
            seatReservationService.releaseSeats(booking.getId());
            return true;
        });
        if (Boolean.TRUE.equals(result)) {
            metrics.expired();
            log.info("BOOKING_EXPIRED bookingId={} (seats released)", id);
        }
        return Boolean.TRUE.equals(result);
    }

    // ------------------------------------------------------------------ payment result (used by Payment from S5)

    /** Outcome of applying a payment result: the booking, plus a rejection to throw AFTER the transaction commits. */
    private record Applied(Booking booking, ApiException rejection, boolean newlyConfirmed) {
        Applied(Booking booking, ApiException rejection) {
            this(booking, rejection, false);
        }
    }

    /**
     * Payment Service reports SUCCESS/FAILED. Booking applies ITS OWN rules (it owns booking state):
     * an expired, cancelled or already-final booking can never be confirmed (SC-12). Repeating the same result is harmless.
     */
    public BookingResponse applyPaymentResult(Long bookingId, PaymentOutcome outcome) {
        MDC.put("bookingId", String.valueOf(bookingId));   // every log line of this request carries the booking id
        try {
            return doApplyPaymentResult(bookingId, outcome);
        } finally {
            MDC.remove("bookingId");
        }
    }

    private BookingResponse doApplyPaymentResult(Long bookingId, PaymentOutcome outcome) {
        Applied applied = tx.execute(status -> {
            Booking booking = lockBooking(bookingId);
            Instant now = Instant.now(clock);
            BookingStatus current = booking.getStatus();

            if (outcome == PaymentOutcome.SUCCESS) {
                if (current == BookingStatus.CONFIRMED) {
                    return new Applied(booking, null);                       // repeated report: no change
                }
                if (current == BookingStatus.PENDING_PAYMENT) {
                    if (!booking.getExpiryAt().isAfter(now)) {
                        // Overdue but the scheduler has not run yet: expire it now, never confirm it.
                        booking.changeStatus(BookingStatus.EXPIRED, now);
                        seatReservationService.releaseSeats(bookingId);
                        log.warn("Payment succeeded for expired booking {}: needs refund (Phase 2)", bookingId);
                        return new Applied(booking, ApiException.conflict("BOOKING_EXPIRED",
                                "The booking expired before payment completed."));
                    }
                    booking.changeStatus(BookingStatus.CONFIRMED, now);
                    seatReservationService.markBooked(bookingId);
                    return new Applied(booking, null, true);
                }
                if (current == BookingStatus.EXPIRED) {
                    log.warn("Payment succeeded for expired booking {}: needs refund (Phase 2)", bookingId);
                    return new Applied(booking, ApiException.conflict("BOOKING_EXPIRED",
                            "The booking expired before payment completed."));
                }
                return new Applied(booking, ApiException.conflict("INVALID_STATE_TRANSITION",
                        "Booking cannot go from " + current + " to CONFIRMED."));
            }

            // outcome == FAILED
            if (current == BookingStatus.PENDING_PAYMENT) {
                booking.changeStatus(BookingStatus.PAYMENT_FAILED, now);
                seatReservationService.releaseSeats(bookingId);
                return new Applied(booking, null);
            }
            if (current == BookingStatus.CONFIRMED) {
                return new Applied(booking, ApiException.conflict("INVALID_STATE_TRANSITION",
                        "Booking cannot go from CONFIRMED to PAYMENT_FAILED."));
            }
            return new Applied(booking, null);   // already PAYMENT_FAILED / EXPIRED / CANCELLED: nothing to do
        });

        if (applied.rejection() != null) {
            throw applied.rejection();
        }
        BookingResponse response = toResponse(applied.booking());
        if (applied.newlyConfirmed()) {
            metrics.confirmed();
            log.info("BOOKING_CONFIRMED bookingId={}", bookingId);
            // AFTER the transaction committed. A failure here is logged by the publisher and never undoes the booking.
            eventPublisher.publishBookingConfirmed(new BookingConfirmedEvent(
                    BookingConfirmedEvent.idFor(response.bookingId()), response.bookingId(), response.customerId(),
                    applied.booking().getCustomerEmail(), response.showId(), response.seatIds(), response.amount(),
                    response.currency(), Instant.now(clock)));
        }
        return response;
    }

    /** Service-to-service read (Payment needs amount + status). No owner check: this is not a customer API. */
    public BookingResponse getBookingForService(Long id) {
        return toResponse(findBooking(id));
    }

    // ------------------------------------------------------------------ helpers

    private CreateBookingResult replay(Booking existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request.");
        }
        return new CreateBookingResult(toResponse(existing), false);
    }

    private void ensureBookable(ShowDto show) {
        boolean openAndPublished = "OPEN".equals(show.status()) && "PUBLISHED".equals(show.eventStatus());
        if (!openAndPublished) {
            throw ApiException.conflict("SHOW_NOT_BOOKABLE",
                    "Only OPEN shows of PUBLISHED events can be booked (show is " + show.status()
                            + ", event is " + show.eventStatus() + ").");
        }
        if (!show.startTime().isAfter(Instant.now(clock))) {
            throw ApiException.conflict("SHOW_NOT_BOOKABLE", "This show has already started.");
        }
    }

    private void ensureSeatsBelongToShow(Long showId, List<Long> seatIds) {
        List<Long> layout = eventShowClient.getSeatLayoutIds(showId);
        List<Long> unknown = seatIds.stream().filter(id -> !layout.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEAT",
                    "Seats " + unknown + " do not belong to the venue of show " + showId + ".");
        }
    }

    private void validateSeatList(List<Long> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw ApiException.badRequest("DUPLICATE_SEATS", "The same seat appears more than once in the request.");
        }
        if (seatIds.size() > maxSeatsPerBooking) {
            throw ApiException.badRequest("TOO_MANY_SEATS",
                    "At most " + maxSeatsPerBooking + " seats can be booked at once.");
        }
    }

    private void requireCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank() || customerId.length() > 64) {
            throw ApiException.badRequest("INVALID_CUSTOMER_ID", "X-Customer-Id must be 1 to 64 characters.");
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1 to 100 characters.");
        }
    }

    /** Unknown booking and someone else's booking look the same (404), so ids cannot be probed. */
    private void requireOwner(Booking booking, String customerId) {
        if (!booking.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + booking.getId() + " not found.");
        }
    }

    private Booking findBooking(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + id + " not found."));
    }

    /** SELECT ... FOR UPDATE: cancel, expiry and payment result take turns on one booking. */
    private Booking lockBooking(Long id) {
        return bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + id + " not found."));
    }

    private BookingResponse toResponse(Booking booking) {
        return BookingResponse.from(booking, seatReservationService.findSeatIdsByBooking(booking.getId()));
    }

    private static ApiException seatUnavailable(List<Long> seatIds) {
        return ApiException.conflict("SEAT_UNAVAILABLE",
                "Seat(s) " + seatIds.stream().map(String::valueOf).collect(Collectors.joining(", "))
                        + " are not available for this show.");
    }

    private static String fingerprint(Long showId, List<Long> sortedSeatIds) {
        return showId + ":" + sortedSeatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
