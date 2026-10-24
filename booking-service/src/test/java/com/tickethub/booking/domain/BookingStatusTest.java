package com.tickethub.booking.domain;

import com.tickethub.booking.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingStatusTest {

    @Test
    void pendingPaymentCanMoveToEveryOutcome() {
        for (BookingStatus next : new BookingStatus[]{BookingStatus.CONFIRMED, BookingStatus.PAYMENT_FAILED,
                BookingStatus.CANCELLED, BookingStatus.EXPIRED}) {
            assertTrue(BookingStatus.PENDING_PAYMENT.canTransitionTo(next), "should allow -> " + next);
        }
    }

    @Test
    void everyOtherStatusIsFinal() {
        for (BookingStatus from : new BookingStatus[]{BookingStatus.CONFIRMED, BookingStatus.PAYMENT_FAILED,
                BookingStatus.CANCELLED, BookingStatus.EXPIRED}) {
            assertTrue(from.allowedNext().isEmpty(), from + " must be final");
        }
    }

    @Test
    void anExpiredBookingCanNeverBeConfirmed() {
        assertFalse(BookingStatus.EXPIRED.canTransitionTo(BookingStatus.CONFIRMED));
        ApiException ex = assertThrows(ApiException.class,
                () -> BookingStateMachine.assertTransition(BookingStatus.EXPIRED, BookingStatus.CONFIRMED));
        assertEquals("INVALID_STATE_TRANSITION", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void validTransitionDoesNotThrow() {
        assertDoesNotThrow(() -> BookingStateMachine.assertTransition(
                BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED));
    }

    @Test
    void activeSeatHoldsAreReservedAndBookedOnly() {
        assertTrue(SeatHoldStatus.ACTIVE.contains(SeatHoldStatus.RESERVED));
        assertTrue(SeatHoldStatus.ACTIVE.contains(SeatHoldStatus.BOOKED));
        assertFalse(SeatHoldStatus.ACTIVE.contains(SeatHoldStatus.RELEASED));
    }
}
