package com.gamagerecruiters.marketplace.modules.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds Stripe gateway configuration and validates required credentials.
 *
 * <p>Stripe is disabled by default. When enabled, both the API secret key
 * and webhook signing secret must be configured before the application
 * can start.</p>
 */
@Component
public final class StripeGatewayProperties {

    private final boolean enabled;
    private final String secretKey;
    private final String webhookSecret;

    public StripeGatewayProperties(
            @Value("${app.payment.stripe.enabled:false}") boolean enabled,
            @Value("${app.payment.stripe.secret-key:}") String secretKey,
            @Value("${app.payment.stripe.webhook-secret:}") String webhookSecret) {

        this.enabled = enabled;
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();

        if (enabled && (this.secretKey.isBlank() || this.webhookSecret.isBlank())) {
            throw new IllegalStateException(
                    "Stripe is enabled but required gateway credentials are missing");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }
}