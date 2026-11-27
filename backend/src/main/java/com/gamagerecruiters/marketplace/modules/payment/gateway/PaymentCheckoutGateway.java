package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

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
     * Determines whether the gateway supports the supplied currency
     * for new hosted checkout sessions.
     *
     * <p>This is a local capability check. It must not submit an
     * external request, modify financial records or depend on
     * whether gateway activation is currently enabled.</p>
     *
     * @param currency trusted payment currency
     * @return true when the currency is supported
     */
    boolean supportsCurrency(String currency);

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
     * Finds an existing checkout session without submitting a new
     * payment creation request.
     *
     * Implementations must validate payment identity, amount, currency
     * and session state before returning a matching session.
     *
     * An empty result does not prove that no gateway request was
     * submitted and must not authorize reservation release.
     *
     * @param paymentId persisted internal payment identifier
     * @param amount trusted payment amount
     * @param currency trusted payment currency
     * @param reservedAt persisted checkout reservation timestamp
     * @return verified existing session, if found
     */
    default Optional<RecoveredCheckoutSession> findCheckoutSession(
            String paymentId,
            BigDecimal amount,
            String currency,
            Instant reservedAt) {

        throw new UnsupportedOperationException(
                "Checkout session recovery is not supported");
    }

    /**
     * Retrieves an already attached Checkout Session without creating
     * another external payment request.
     *
     * Implementations must verify the session identifier, internal
     * payment identity, amount, currency and supported session state.
     *
     * Retrieval must never confirm payment capture, replace a session
     * or release an existing checkout reservation.
     *
     * @param sessionId persisted gateway session identifier
     * @param paymentId persisted internal payment identifier
     * @param amount trusted payment amount
     * @param currency trusted payment currency
     * @return verified existing Checkout Session
     */
    default RecoveredCheckoutSession retrieveCheckoutSession(
            String sessionId,
            String paymentId,
            BigDecimal amount,
            String currency) {

        throw new UnsupportedOperationException(
                "Checkout session retrieval is not supported");
    }


    /**
     * Verified session discovered through read-only gateway recovery.
     *
     * The checkout URL may be absent for a completed session.
     * Recovering a session reference does not itself confirm payment capture.
     */
    record RecoveredCheckoutSession(
            String sessionId,
            String checkoutUrl,
            String status,
            String paymentStatus) {
    }

    /**
     * Result returned by a hosted checkout gateway.
     *
     * @param sessionId unique gateway checkout session identifier
     * @param checkoutUrl hosted checkout URL
     */
    record CheckoutSession(String sessionId, String checkoutUrl) {
    }
}
