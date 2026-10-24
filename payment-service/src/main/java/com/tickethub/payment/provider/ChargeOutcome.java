package com.tickethub.payment.provider;

/** What the payment provider answered. */
public record ChargeOutcome(boolean success, String failureReason) {
    public static ChargeOutcome ok() { return new ChargeOutcome(true, null); }
    public static ChargeOutcome declined(String reason) { return new ChargeOutcome(false, reason); }
}
