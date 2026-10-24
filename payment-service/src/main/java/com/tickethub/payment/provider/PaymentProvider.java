package com.tickethub.payment.provider;

import java.math.BigDecimal;

/**
 * The seam to the outside world. Today: {@link SimulatedPaymentProvider}. A real provider sandbox
 * (Razorpay/Stripe test mode) is another implementation of this interface; nothing else changes.
 */
public interface PaymentProvider {
    String name();

    ChargeOutcome charge(String providerReference, BigDecimal amount, String currency, SimulationHints hints);
}
