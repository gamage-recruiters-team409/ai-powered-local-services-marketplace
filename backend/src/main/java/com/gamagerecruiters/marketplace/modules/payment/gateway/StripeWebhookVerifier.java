package com.gamagerecruiters.marketplace.modules.payment.gateway;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

/**
 * Verifies the authenticity of incoming Stripe webhook events.
 *
 * <p>Signature verification must use the exact raw HTTP request body.
 * The payload must not be parsed or modified before verification.</p>
 *
 * <p>This component verifies events only. It does not update payment
 * statuses or execute financial operations.</p>
 */
@Component
public final class StripeWebhookVerifier {

    private final StripeGatewayProperties properties;

    public StripeWebhookVerifier(StripeGatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * Verifies a Stripe webhook signature and returns the authenticated event.
     *
     * @param payload exact raw webhook request body
     * @param signature value of the Stripe-Signature HTTP header
     * @return verified Stripe event
     * @throws IllegalStateException if Stripe is disabled
     * @throws IllegalArgumentException if required webhook input is missing
     * @throws SignatureVerificationException if signature verification fails
     */
    public Event verify(String payload, String signature)
            throws SignatureVerificationException {

        if (!properties.isEnabled()) {
            throw new IllegalStateException("Stripe webhook verification is disabled");
        }

        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Webhook payload is required");
        }

        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Stripe-Signature header is required");
        }

        // Stripe validates the signed payload and timestamp tolerance.
        // Never trust event data before this verification succeeds.
        return Webhook.constructEvent(
                payload,
                signature,
                properties.getWebhookSecret());
    }
}
