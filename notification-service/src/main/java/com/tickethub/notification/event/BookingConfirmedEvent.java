package com.tickethub.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Notification's own copy of the event contract (ST-19). Unknown fields are ignored, so Booking can add fields safely. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingConfirmedEvent(String eventId, Long bookingId, String customerId, String recipient,
                                    Long showId, List<Long> seatIds, BigDecimal amount, String currency,
                                    Instant confirmedAt) {
}
