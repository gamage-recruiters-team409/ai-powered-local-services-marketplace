package com.gamagerecruiters.marketplace.modules.payment.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Restricts Stripe Checkout redirects to explicitly configured
 * server-side destinations.
 *
 * Missing configuration prevents checkout initiation without
 * preventing unrelated application features from starting.
 */
@Component
public final class StripeCheckoutRedirectPolicy {

    private final String trustedSuccessUrl;
    private final String trustedCancelUrl;

    public StripeCheckoutRedirectPolicy(
            @Value("${app.payment.stripe.checkout.success-url:}")
            String trustedSuccessUrl,
            @Value("${app.payment.stripe.checkout.cancel-url:}")
            String trustedCancelUrl) {

        this.trustedSuccessUrl = validateConfiguredUrl(
                trustedSuccessUrl);

        this.trustedCancelUrl = validateConfiguredUrl(
                trustedCancelUrl);
    }

    /**
     * Rejects unapproved destinations before any financial
     * reservation or external gateway request is created.
     */
    public void validate(String successUrl, String cancelUrl) {

        if (trustedSuccessUrl.isBlank()
                || trustedCancelUrl.isBlank()) {

            throw new IllegalStateException(
                    "Trusted Checkout redirect URLs are not configured.");
        }

        if (!trustedSuccessUrl.equals(successUrl)
                || !trustedCancelUrl.equals(cancelUrl)) {

            throw new IllegalArgumentException(
                    "Checkout redirect URL is not approved.");
        }
    }

    /**
     * Validates trusted configuration without accepting arbitrary
     * external URLs or ambiguous URI representations.
     */
    private static String validateConfiguredUrl(String value) {

        if (value == null || value.isBlank()) {
            return "";
        }

        final URI uri;

        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid Checkout redirect configuration.");
        }

        if (!uri.isAbsolute()
                || uri.isOpaque()
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535
                || uri.getPort() == 0) {

            throw new IllegalStateException(
                    "Invalid Checkout redirect configuration.");
        }

        boolean secure =
                "https".equalsIgnoreCase(uri.getScheme());

        boolean localDevelopment =
                "http".equalsIgnoreCase(uri.getScheme())
                        && ("localhost".equalsIgnoreCase(uri.getHost())
                        || "127.0.0.1".equals(uri.getHost()));

        if (!secure && !localDevelopment) {
            throw new IllegalStateException(
                    "Checkout redirects require HTTPS or approved localhost HTTP.");
        }

        return value;
    }
}