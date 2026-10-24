package com.tickethub.payment.domain;

import com.tickethub.payment.exception.ApiException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_reference", nullable = false, unique = true)
    private String providerReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "booking_synced", nullable = false)
    private boolean bookingSynced;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(Long bookingId, String customerId, BigDecimal amount, String currency, String provider,
                   String providerReference, Instant now) {
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.provider = provider;
        this.providerReference = providerReference;
        this.status = PaymentStatus.INITIATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Single place where the state machine is enforced. */
    public void moveTo(PaymentStatus next, String reason, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                    "Payment cannot move from " + status + " to " + next + ".");
        }
        this.status = next;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public void markSynced(Instant now) {
        this.bookingSynced = true;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getProvider() { return provider; }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public boolean isBookingSynced() { return bookingSynced; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
