package com.tickethub.payment.dto;

import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(Long paymentId, Long bookingId, BigDecimal amount, String currency,
                              PaymentStatus status, String failureReason, Instant createdAt) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getAmount(), p.getCurrency(), p.getStatus(),
                p.getFailureReason(), p.getCreatedAt());
    }
}
