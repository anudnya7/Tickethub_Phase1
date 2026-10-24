package com.tickethub.booking.integration;

import com.tickethub.booking.client.EventShowClient;
import com.tickethub.booking.client.ShowDto;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.BookingStatus;
import com.tickethub.booking.domain.SeatHoldStatus;
import com.tickethub.booking.dto.BookingResponse;
import com.tickethub.booking.dto.CreateBookingRequest;
import com.tickethub.booking.dto.CreateBookingResult;
import com.tickethub.booking.dto.PaymentOutcome;
import com.tickethub.booking.dto.SeatStateResponse;
import com.tickethub.booking.event.BookingConfirmedEvent;
import com.tickethub.booking.event.BookingEventPublisher;
import com.tickethub.booking.exception.ApiException;
import com.tickethub.booking.repository.BookingRepository;
import com.tickethub.booking.repository.BookingSeatRepository;
import com.tickethub.booking.service.BookingService;
import com.tickethub.booking.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the booking rules against a REAL PostgreSQL (Flyway migration, Hibernate validation, the partial unique
 * index, row locks, transactions). Event/Show is mocked. Skipped automatically if Docker is not running.
 * Covers ST-06, ST-07, ST-08 (concurrency), ST-09 (expiry), ST-52 (view/cancel), ST-53 (idempotency),
 * ST-55/56 (payment results), ST-19 (event published once).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BookingFlowIntegrationTest {

    private static final long SHOW_ID = 10L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** A clock the test can move forward, to make reservations expire without waiting. */
    static class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @Autowired
    private BookingService bookingService;
    @Autowired
    private SeatReservationService seatReservationService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private BookingSeatRepository bookingSeatRepository;
    @Autowired
    private MutableClock clock;

    @MockitoBean
    private EventShowClient eventShowClient;
    @MockitoBean
    private BookingEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        bookingSeatRepository.deleteAll();
        bookingRepository.deleteAll();
        clock.set(Instant.now());
        stubShow("OPEN", "PUBLISHED", clock.instant().plus(Duration.ofDays(1)));
        when(eventShowClient.getSeatLayoutIds(SHOW_ID))
                .thenReturn(LongStream.rangeClosed(1, 10).boxed().toList());
    }

    private void stubShow(String showStatus, String eventStatus, Instant start) {
        when(eventShowClient.getShow(SHOW_ID)).thenReturn(new ShowDto(SHOW_ID, 1L, eventStatus, 1L, start,
                start.plus(Duration.ofHours(2)), new BigDecimal("250.00"), "INR", showStatus));
    }

    private CreateBookingResult book(String customer, String key, Long... seats) {
        return bookingService.createBooking(customer, customer + "@test.dev", key,
                new CreateBookingRequest(SHOW_ID, List.of(seats)));
    }

    private String errorCodeOf(Runnable action) {
        return assertThrows(ApiException.class, action::run).getCode();
    }

    // ---------------------------------------------------------------- ST-06 / ST-07

    @Test
    void newBookingIsPendingPaymentWithPriceSnapshotAndExpiry() {
        CreateBookingResult result = book("cust-1", "k1", 1L, 2L);

        assertTrue(result.created());
        BookingResponse b = result.booking();
        assertEquals(BookingStatus.PENDING_PAYMENT, b.status());
        assertEquals(new BigDecimal("500.00"), b.amount());      // 250.00 x 2 seats
        assertEquals("INR", b.currency());
        assertEquals(List.of(1L, 2L), b.seatIds());
        assertEquals(clock.instant().plus(Duration.ofMinutes(10)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                b.expiryAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertEquals(List.of(new SeatStateResponse(1L, "RESERVED"), new SeatStateResponse(2L, "RESERVED")),
                seatReservationService.getSeatStates(SHOW_ID).stream()
                        .sorted((x, y) -> x.seatId().compareTo(y.seatId())).toList());
    }

    @Test
    void aReservedSeatCannotBeReservedByAnotherCustomer() {
        book("cust-1", "k1", 1L);

        assertEquals("SEAT_UNAVAILABLE", errorCodeOf(() -> book("cust-2", "k2", 1L, 3L)));
        // all-or-nothing: seat 3 must NOT have been taken by the failed attempt
        assertEquals(1, seatReservationService.getSeatStates(SHOW_ID).size());
    }

    @Test
    void bookingRulesAreEnforced() {
        stubShow("SCHEDULED", "PUBLISHED", clock.instant().plus(Duration.ofDays(1)));
        assertEquals("SHOW_NOT_BOOKABLE", errorCodeOf(() -> book("c", "k1", 1L)));

        stubShow("OPEN", "DRAFT", clock.instant().plus(Duration.ofDays(1)));
        assertEquals("SHOW_NOT_BOOKABLE", errorCodeOf(() -> book("c", "k2", 1L)));

        stubShow("OPEN", "PUBLISHED", clock.instant().minus(Duration.ofHours(1)));
        assertEquals("SHOW_NOT_BOOKABLE", errorCodeOf(() -> book("c", "k3", 1L)));

        stubShow("OPEN", "PUBLISHED", clock.instant().plus(Duration.ofDays(1)));
        assertEquals("INVALID_SEAT", errorCodeOf(() -> book("c", "k4", 999L)));
        assertEquals("DUPLICATE_SEATS", errorCodeOf(() -> book("c", "k5", 1L, 1L)));
        assertEquals("TOO_MANY_SEATS", errorCodeOf(() -> bookingService.createBooking("c", null, "k6",
                new CreateBookingRequest(SHOW_ID, LongStream.rangeClosed(1, 11).boxed().toList()))));
        assertEquals(0, bookingRepository.count());
    }

    @Test
    void anUnreachableEventShowServiceGivesAControlled503() {
        when(eventShowClient.getShow(SHOW_ID)).thenThrow(
                ApiException.unavailable("EVENT_SHOW_SERVICE_UNAVAILABLE", "down"));

        ApiException ex = assertThrows(ApiException.class, () -> book("c", "k1", 1L));

        assertEquals(503, ex.getStatus().value());
        assertEquals(0, bookingRepository.count());
    }

    // ---------------------------------------------------------------- ST-08

    @Test
    void whenManyCustomersRaceForTheSameSeatExactlyOneWins() throws Exception {
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();

        for (int i = 0; i < contenders; i++) {
            final int n = i;
            outcomes.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    book("racer-" + n, "race-key-" + n, 5L);
                    return "WON";
                } catch (ApiException e) {
                    return e.getCode();
                }
            }));
        }
        ready.await();
        go.countDown();

        int won = 0;
        int rejected = 0;
        for (Future<String> f : outcomes) {
            String outcome = f.get(30, TimeUnit.SECONDS);
            if (outcome.equals("WON")) {
                won++;
            } else {
                assertEquals("SEAT_UNAVAILABLE", outcome);   // the loser gets a CONTROLLED conflict
                rejected++;
            }
        }
        pool.shutdown();

        assertEquals(1, won, "exactly one request may win the seat");
        assertEquals(contenders - 1, rejected);
        // the database agrees: one active hold, and only the winner's booking exists
        assertEquals(1, bookingSeatRepository.findByShowIdAndStatusIn(SHOW_ID, SeatHoldStatus.ACTIVE).size());
        assertEquals(1, bookingRepository.count());
    }

    // ---------------------------------------------------------------- ST-53

    @Test
    void retryingWithTheSameIdempotencyKeyReturnsTheOriginalBooking() {
        CreateBookingResult first = book("cust-1", "retry-key", 1L, 2L);
        CreateBookingResult retry = book("cust-1", "retry-key", 2L, 1L);   // same seats, any order

        assertTrue(first.created());
        assertEquals(false, retry.created());
        assertEquals(first.booking().bookingId(), retry.booking().bookingId());
        assertEquals(1, bookingRepository.count());
        assertEquals(2, bookingSeatRepository.count());
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        book("cust-1", "key-x", 1L);

        assertEquals("IDEMPOTENCY_KEY_REUSED", errorCodeOf(() -> book("cust-1", "key-x", 2L)));
        assertEquals(1, bookingRepository.count());
    }

    @Test
    void theSameKeyFromADifferentCustomerIsADifferentRequest() {
        CreateBookingResult a = book("cust-1", "shared-key", 1L);
        CreateBookingResult b = book("cust-2", "shared-key", 2L);

        assertNotEquals(a.booking().bookingId(), b.booking().bookingId());
    }

    // ---------------------------------------------------------------- ST-52

    @Test
    void customerSeesOnlyTheirOwnBooking() {
        Long id = book("cust-1", "k1", 1L).booking().bookingId();

        assertEquals(id, bookingService.getBooking(id, "cust-1").bookingId());
        assertEquals("BOOKING_NOT_FOUND", errorCodeOf(() -> bookingService.getBooking(id, "cust-2")));
        assertEquals("BOOKING_NOT_FOUND", errorCodeOf(() -> bookingService.getBooking(424242L, "cust-1")));
    }

    @Test
    void cancellingReleasesTheSeatsSoAnotherCustomerCanBookThem() {
        Long id = book("cust-1", "k1", 1L, 2L).booking().bookingId();

        assertEquals("BOOKING_NOT_FOUND", errorCodeOf(() -> bookingService.cancelBooking(id, "cust-2")));
        BookingResponse cancelled = bookingService.cancelBooking(id, "cust-1");

        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertEquals(0, seatReservationService.getSeatStates(SHOW_ID).size());
        assertEquals(BookingStatus.PENDING_PAYMENT, book("cust-2", "k2", 1L, 2L).booking().status());
        assertEquals("INVALID_STATE_TRANSITION", errorCodeOf(() -> bookingService.cancelBooking(id, "cust-1")));
    }

    // ---------------------------------------------------------------- ST-55 / ST-56 / ST-19

    @Test
    void successfulPaymentConfirmsTheBookingBooksTheSeatsAndPublishesTheEventOnce() {
        Long id = book("cust-1", "k1", 1L, 2L).booking().bookingId();

        BookingResponse confirmed = bookingService.applyPaymentResult(id, PaymentOutcome.SUCCESS);
        BookingResponse again = bookingService.applyPaymentResult(id, PaymentOutcome.SUCCESS);   // duplicate report

        assertEquals(BookingStatus.CONFIRMED, confirmed.status());
        assertEquals(BookingStatus.CONFIRMED, again.status());
        assertEquals(List.of("BOOKED", "BOOKED"), seatReservationService.getSeatStates(SHOW_ID).stream()
                .map(SeatStateResponse::status).toList());

        ArgumentCaptor<BookingConfirmedEvent> captor = ArgumentCaptor.forClass(BookingConfirmedEvent.class);
        verify(eventPublisher, times(1)).publishBookingConfirmed(captor.capture());
        BookingConfirmedEvent event = captor.getValue();
        assertEquals("booking-confirmed-" + id, event.eventId());
        assertEquals("cust-1@test.dev", event.recipient());
        assertEquals(new BigDecimal("500.00"), event.amount());
        assertEquals(List.of(1L, 2L), event.seatIds());
    }

    @Test
    void aConfirmedBookingCannotBeCancelled() {
        Long id = book("cust-1", "k1", 1L).booking().bookingId();
        bookingService.applyPaymentResult(id, PaymentOutcome.SUCCESS);

        assertEquals("CANCELLATION_NOT_ALLOWED", errorCodeOf(() -> bookingService.cancelBooking(id, "cust-1")));
    }

    @Test
    void failedPaymentMarksTheBookingFailedAndFreesTheSeats() {
        Long id = book("cust-1", "k1", 1L, 2L).booking().bookingId();

        BookingResponse failed = bookingService.applyPaymentResult(id, PaymentOutcome.FAILED);

        assertEquals(BookingStatus.PAYMENT_FAILED, failed.status());
        assertEquals(0, seatReservationService.getSeatStates(SHOW_ID).size());
        verify(eventPublisher, never()).publishBookingConfirmed(any());
        assertEquals(BookingStatus.PENDING_PAYMENT, book("cust-2", "k2", 1L, 2L).booking().status());
    }

    // ---------------------------------------------------------------- ST-09

    @Test
    void anOverdueBookingExpiresAndItsSeatsBecomeAvailableAgain() {
        Long id = book("cust-1", "k1", 1L).booking().bookingId();

        assertEquals(0, bookingService.expireOverdueBookings());           // not overdue yet
        clock.advance(Duration.ofMinutes(11));
        assertEquals(1, bookingService.expireOverdueBookings());

        assertEquals(BookingStatus.EXPIRED, bookingService.getBooking(id, "cust-1").status());
        assertEquals(0, seatReservationService.getSeatStates(SHOW_ID).size());
        assertEquals(BookingStatus.PENDING_PAYMENT, book("cust-2", "k2", 1L).booking().status());
    }

    @Test
    void anExpiredBookingCanNeverBeConfirmed() {
        Long id = book("cust-1", "k1", 1L).booking().bookingId();
        clock.advance(Duration.ofMinutes(11));
        bookingService.expireOverdueBookings();

        assertEquals("BOOKING_EXPIRED", errorCodeOf(() -> bookingService.applyPaymentResult(id, PaymentOutcome.SUCCESS)));

        assertEquals(BookingStatus.EXPIRED, bookingService.getBooking(id, "cust-1").status());
        verify(eventPublisher, never()).publishBookingConfirmed(any());
    }

    @Test
    void paymentArrivingAfterExpiryIsRejectedEvenBeforeTheSchedulerHasRun() {
        Long id = book("cust-1", "k1", 1L).booking().bookingId();
        clock.advance(Duration.ofMinutes(11));            // overdue, but expireOverdueBookings() has NOT run

        assertEquals("BOOKING_EXPIRED", errorCodeOf(() -> bookingService.applyPaymentResult(id, PaymentOutcome.SUCCESS)));

        Booking stored = bookingRepository.findById(id).orElseThrow();
        assertEquals(BookingStatus.EXPIRED, stored.getStatus());
        assertEquals(0, seatReservationService.getSeatStates(SHOW_ID).size());
    }
}
