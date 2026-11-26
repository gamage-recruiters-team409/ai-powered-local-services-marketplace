package com.gamagerecruiters.marketplace.modules.payment.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for Stripe gateway configuration validation.
 *
 * Ensures that Stripe remains optional by default and that enabling
 * the gateway requires both credentials without exposing secret values.
 */
class StripeGatewayPropertiesTest {

    @Test
    void disabledGatewayAllowsMissingCredentials() {
        StripeGatewayProperties properties =
                new StripeGatewayProperties(false, "", "");

        assertFalse(properties.isEnabled());
        assertEquals("", properties.getSecretKey());
        assertEquals("", properties.getWebhookSecret());
    }

    @Test
    void enabledGatewayRejectsMissingApiSecretKey() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new StripeGatewayProperties(true, "", "whsec_test_secret"));

        assertTrue(exception.getMessage().contains("credentials are missing"));
        assertFalse(exception.getMessage().contains("whsec_test_secret"));
    }

    @Test
    void enabledGatewayRejectsMissingWebhookSecret() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new StripeGatewayProperties(true, "sk_test_secret", ""));

        assertTrue(exception.getMessage().contains("credentials are missing"));
        assertFalse(exception.getMessage().contains("sk_test_secret"));
    }

    @Test
    void enabledGatewayAcceptsConfiguredCredentials() {
        StripeGatewayProperties properties =
                new StripeGatewayProperties(
                        true,
                        " sk_test_secret ",
                        " whsec_test_secret ");

        assertTrue(properties.isEnabled());
        assertEquals("sk_test_secret", properties.getSecretKey());
        assertEquals("whsec_test_secret", properties.getWebhookSecret());
    }

    @Test
    void enabledGatewayRejectsWhitespaceOnlyCredentials() {
        assertThrows(
                IllegalStateException.class,
                () -> new StripeGatewayProperties(true, "   ", "whsec_test_secret"));

        assertThrows(
                IllegalStateException.class,
                () -> new StripeGatewayProperties(true, "sk_test_secret", "   "));
    }
}