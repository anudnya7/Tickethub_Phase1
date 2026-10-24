package com.tickethub.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/** INITIATED -> SUCCESS | FAILED | PENDING (provider timed out); PENDING -> SUCCESS | FAILED (webhook). */
public enum PaymentStatus {
    INITIATED, PENDING, SUCCESS, FAILED;

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case INITIATED -> EnumSet.of(SUCCESS, FAILED, PENDING);
            case PENDING -> EnumSet.of(SUCCESS, FAILED);
            case SUCCESS, FAILED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED;
    }
}
