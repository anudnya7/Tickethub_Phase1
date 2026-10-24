package com.tickethub.payment.domain;

import com.tickethub.payment.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PaymentStatusTest {

    @Test
    void initiatedCanReachEveryOutcomeAndPendingCanStillResolve() {
        assertTrue(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.SUCCESS));
        assertTrue(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.FAILED));
        assertTrue(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.PENDING));
        assertTrue(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.SUCCESS));
        assertTrue(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED));
    }

    @Test
    void finalStatesNeverChange() {
        assertTrue(PaymentStatus.SUCCESS.allowedNext().isEmpty());
        assertTrue(PaymentStatus.FAILED.allowedNext().isEmpty());
        assertFalse(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.INITIATED));
    }

    @Test
    void entityRejectsIllegalTransition() {
        Payment p = new Payment(1L, "c", new BigDecimal("10.00"), "INR", "SIMULATED", "ref", Instant.now());
        p.moveTo(PaymentStatus.SUCCESS, null, Instant.now());
        ApiException ex = assertThrows(ApiException.class, () -> p.moveTo(PaymentStatus.FAILED, "x", Instant.now()));
        assertEquals("INVALID_STATE_TRANSITION", ex.getCode());
    }
}
