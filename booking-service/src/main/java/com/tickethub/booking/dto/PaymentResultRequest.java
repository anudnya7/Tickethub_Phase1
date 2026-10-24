package com.tickethub.booking.dto;

import jakarta.validation.constraints.NotNull;

/** Payment Service tells Booking how a payment ended. Booking still applies its own rules. */
public record PaymentResultRequest(@NotNull PaymentOutcome result, Long paymentId) {
}
