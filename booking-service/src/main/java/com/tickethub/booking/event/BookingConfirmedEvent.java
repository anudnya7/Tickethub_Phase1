package com.tickethub.booking.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * EVENT CONTRACT (ST-19): "this booking is confirmed". Published to Kafka topic booking-confirmed as JSON.
 *
 *   eventId      stable per booking ("booking-confirmed-{bookingId}") so consumers can detect duplicates
 *   bookingId    the booking
 *   customerId   who booked
 *   recipient    where the confirmation email goes
 *   showId       the show
 *   seatIds      the booked seats
 *   amount       price snapshot paid
 *   currency     ISO currency code
 *   confirmedAt  when it was confirmed (UTC)
 */
public record BookingConfirmedEvent(String eventId, Long bookingId, String customerId, String recipient,
                                    Long showId, List<Long> seatIds, BigDecimal amount, String currency,
                                    Instant confirmedAt) {

    public static String idFor(Long bookingId) {
        return "booking-confirmed-" + bookingId;
    }
}
