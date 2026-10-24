package com.tickethub.payment.integration;

import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.client.BookingDto;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.PaymentStatus;
import com.tickethub.payment.dto.CreatePaymentResult;
import com.tickethub.payment.dto.WebhookResponse;
import com.tickethub.payment.exception.ApiException;
import com.tickethub.payment.provider.SimulationHints;
import com.tickethub.payment.repository.PaymentRepository;
import com.tickethub.payment.repository.WebhookEventRepository;
import com.tickethub.payment.service.PaymentService;
import com.tickethub.payment.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payment rules against a real PostgreSQL; Booking Service is mocked. Skipped without Docker. */
@SpringBootTest(properties = {"payment.provider-timeout-ms=400", "payment.sync.enabled=false",
        "payment.webhook.secret=test-secret"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PaymentFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PaymentService paymentService;
    @Autowired WebhookService webhookService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired WebhookEventRepository eventRepository;
    @Autowired MockMvc mockMvc;
    @MockitoBean BookingClient bookingClient;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        paymentRepository.deleteAll();
        reset(bookingClient);
        resilient.breaker("payment-provider").reset();   // tests must not inherit an open breaker
        stubBooking(1L, "cust-1", "PENDING_PAYMENT", Instant.now().plusSeconds(600));
    }

    private void stubBooking(Long id, String customer, String status, Instant expiry) {
        when(bookingClient.getBooking(id)).thenReturn(
                new BookingDto(id, customer, new BigDecimal("500.00"), "INR", status, expiry));
    }

    private String code(Runnable r) {
        return assertThrows(ApiException.class, r::run).getCode();
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String webhookBody(String eventId, String ref, String result) {
        return "{\"eventId\":\"" + eventId + "\",\"providerReference\":\"" + ref + "\",\"result\":\"" + result + "\"}";
    }

    @Test
    void statusEndpointAndStandardErrorFormat() throws Exception {
        mockMvc.perform(get("/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("payment-service"));
        mockMvc.perform(get("/does-not-exist")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void successfulPaymentUsesTheBookingAmountAndReportsToBooking() {
        CreatePaymentResult r = paymentService.pay("cust-1", 1L, SimulationHints.none());

        assertTrue(r.created());
        assertEquals(PaymentStatus.SUCCESS, r.payment().status());
        assertEquals(new BigDecimal("500.00"), r.payment().amount());
        verify(bookingClient).reportResult(eq(1L), eq("SUCCESS"), any());
        assertTrue(paymentRepository.findByBookingId(1L).orElseThrow().isBookingSynced());
    }

    @Test
    void declinedPaymentIsFailedAndReported() {
        CreatePaymentResult r = paymentService.pay("cust-1", 1L, new SimulationHints("FAILED", null));

        assertEquals(PaymentStatus.FAILED, r.payment().status());
        assertEquals("CARD_DECLINED", r.payment().failureReason());
        verify(bookingClient).reportResult(eq(1L), eq("FAILED"), any());
    }

    @Test
    void payingTwiceReturnsTheSamePaymentAndChargesOnce() {
        CreatePaymentResult first = paymentService.pay("cust-1", 1L, SimulationHints.none());
        CreatePaymentResult second = paymentService.pay("cust-1", 1L, SimulationHints.none());

        assertFalse(second.created());
        assertEquals(first.payment().paymentId(), second.payment().paymentId());
        assertEquals(1, paymentRepository.count());
        verify(bookingClient, times(1)).reportResult(any(), any(), any());
    }

    @Test
    void concurrentPayRequestsForOneBookingCreateExactlyOnePayment() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Future<Long>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return paymentService.pay("cust-1", 1L, SimulationHints.none()).payment().paymentId();
            }));
        }
        go.countDown();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Long> f : results) {
            ids.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, ids.size());
        assertEquals(1, paymentRepository.count());
    }

    @Test
    void bookingRulesAreChecked() {
        assertEquals("BOOKING_NOT_FOUND", code(() -> paymentService.pay("someone-else", 1L, SimulationHints.none())));

        stubBooking(1L, "cust-1", "CONFIRMED", Instant.now().plusSeconds(600));
        assertEquals("BOOKING_NOT_PAYABLE", code(() -> paymentService.pay("cust-1", 1L, SimulationHints.none())));

        stubBooking(1L, "cust-1", "PENDING_PAYMENT", Instant.now().minusSeconds(5));
        assertEquals("BOOKING_EXPIRED", code(() -> paymentService.pay("cust-1", 1L, SimulationHints.none())));
        assertEquals(0, paymentRepository.count());
    }

    @Test
    void ifBookingIsDownTheResultIsKeptAndRetriedLater() {
        doThrow(ApiException.unavailable("BOOKING_SERVICE_UNAVAILABLE", "down"))
                .when(bookingClient).reportResult(any(), any(), any());

        CreatePaymentResult r = paymentService.pay("cust-1", 1L, SimulationHints.none());

        assertEquals(PaymentStatus.SUCCESS, r.payment().status());           // customer still gets the answer
        assertFalse(paymentRepository.findByBookingId(1L).orElseThrow().isBookingSynced());

        doNothing().when(bookingClient).reportResult(any(), any(), any());   // Booking is back
        assertEquals(1, paymentService.retryUnsyncedPayments());
        assertTrue(paymentRepository.findByBookingId(1L).orElseThrow().isBookingSynced());
    }

    @Test
    void providerTimeoutLeavesPaymentPendingAndReturns504() {
        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.pay("cust-1", 1L, new SimulationHints(null, 3000L)));

        assertEquals(504, ex.getStatus().value());
        Payment p = paymentRepository.findByBookingId(1L).orElseThrow();
        assertEquals(PaymentStatus.PENDING, p.getStatus());
        verify(bookingClient, never()).reportResult(any(), any(), any());
        // retry does not create a second payment
        assertFalse(paymentService.pay("cust-1", 1L, SimulationHints.none()).created());
    }

    @Test
    void webhookResolvesAPendingPayment() throws Exception {
        assertThrows(ApiException.class, () -> paymentService.pay("cust-1", 1L, new SimulationHints(null, 3000L)));
        String ref = paymentRepository.findByBookingId(1L).orElseThrow().getProviderReference();
        String body = webhookBody("evt-1", ref, "SUCCESS");

        assertEquals("APPLIED", webhookService.handle(body, sign(body)).result());

        assertEquals(PaymentStatus.SUCCESS, paymentRepository.findByBookingId(1L).orElseThrow().getStatus());
        verify(bookingClient).reportResult(eq(1L), eq("SUCCESS"), any());
    }

    @Test
    void webhookWithBadSignatureIsRejected() {
        String body = webhookBody("evt-1", "whatever", "SUCCESS");
        assertEquals("INVALID_SIGNATURE", code(() -> webhookService.handle(body, "deadbeef")));
        assertEquals("INVALID_SIGNATURE", code(() -> webhookService.handle(body, null)));
    }

    @Test
    void replayedWebhookIsIgnoredAndLateConflictingWebhookCannotChangeAFinalPayment() throws Exception {
        paymentService.pay("cust-1", 1L, SimulationHints.none());               // SUCCESS
        String ref = paymentRepository.findByBookingId(1L).orElseThrow().getProviderReference();

        String conflicting = webhookBody("evt-9", ref, "FAILED");
        assertEquals("IGNORED", webhookService.handle(conflicting, sign(conflicting)).result());
        WebhookResponse replay = webhookService.handle(conflicting, sign(conflicting));
        assertEquals("DUPLICATE", replay.result());

        assertEquals(PaymentStatus.SUCCESS, paymentRepository.findByBookingId(1L).orElseThrow().getStatus());
    }

    @Test
    void customerSeesOnlyTheirOwnPayment() {
        Long id = paymentService.pay("cust-1", 1L, SimulationHints.none()).payment().paymentId();

        assertEquals(id, paymentService.getPayment(id, "cust-1").paymentId());
        assertEquals("PAYMENT_NOT_FOUND", code(() -> paymentService.getPayment(id, "cust-2")));
    }

    @Autowired com.tickethub.payment.resilience.ResilientCaller resilient;

    @Test
    void repeatedProviderTimeoutsOpenTheCircuitAndNewPaymentsFailFastWith503() {
        // each timed-out attempt is a failure for the breaker (window 10 / min 5 calls / 50%)
        for (long booking = 100; booking < 105; booking++) {
            final long id = booking;
            stubBooking(id, "cust-1", "PENDING_PAYMENT", Instant.now().plusSeconds(600));
            assertEquals(504, assertThrows(ApiException.class,
                    () -> paymentService.pay("cust-1", id, new SimulationHints(null, 3000L))).getStatus().value());
        }
        stubBooking(200L, "cust-1", "PENDING_PAYMENT", Instant.now().plusSeconds(600));

        ApiException ex = assertThrows(ApiException.class,
                () -> paymentService.pay("cust-1", 200L, SimulationHints.none()));

        assertEquals(503, ex.getStatus().value());
        assertEquals("PAYMENT_PROVIDER_UNAVAILABLE", ex.getCode());
        assertTrue(paymentRepository.findByBookingId(200L).isEmpty(), "nothing is recorded when we fail fast");
    }
}
