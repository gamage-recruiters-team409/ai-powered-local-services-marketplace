package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;

/**
 * Gateway-independent contract for creating hosted payment checkout sessions.
 *
 * <p>
 * Callers must supply a persisted payment attempt whose amount and currency
 * originate from trusted booking data. Redirect URLs must be approved by
 * server-side configuration rather than accepted from arbitrary client input.
 * </p>
 */
public interface PaymentCheckoutGateway {

    /**
     * Creates or retrieves a hosted checkout session for a payment attempt.
     *
     * @param paymentId persisted internal payment attempt identifier
     * @param amount trusted payment amount
     * @param currency ISO 4217 payment currency
     * @param successUrl server-approved successful checkout redirect URL
     * @param cancelUrl server-approved cancelled checkout redirect URL
     * @return gateway session reference and hosted checkout URL
     */
    CheckoutSession createCheckoutSession(
            String paymentId,
            BigDecimal amount,
            String currency,
            String successUrl,
            String cancelUrl);

    /**
     * Result returned by a hosted checkout gateway.
     *
     * @param sessionId unique gateway checkout session identifier
     * @param checkoutUrl hosted checkout URL
     */
    record CheckoutSession(String sessionId, String checkoutUrl) {
    }
}
