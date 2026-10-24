package com.tickethub.booking.domain;

import java.util.Set;

/**
 * PENDING_PAYMENT is the only state that can change. Everything else is final in Phase 1
 * (a CONFIRMED booking cannot be cancelled: refunds are Phase 2).
 */
public enum BookingStatus {
    PENDING_PAYMENT, CONFIRMED, PAYMENT_FAILED, CANCELLED, EXPIRED;

    public Set<BookingStatus> allowedNext() {
        return this == PENDING_PAYMENT
                ? Set.of(CONFIRMED, PAYMENT_FAILED, CANCELLED, EXPIRED)
                : Set.of();
    }

    public boolean canTransitionTo(BookingStatus next) {
        return allowedNext().contains(next);
    }
}
