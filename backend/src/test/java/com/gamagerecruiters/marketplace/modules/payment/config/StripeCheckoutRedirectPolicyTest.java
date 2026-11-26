package com.gamagerecruiters.marketplace.modules.payment.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies server-controlled Checkout redirect configuration
 * and fail-closed destination validation.
 */
class StripeCheckoutRedirectPolicyTest {

    private static final String SUCCESS =
            "https://example.com/payment/success";

    private static final String CANCEL =
            "https://example.com/payment/cancel";

    @Test
    void acceptsExactlyConfiguredHttpsUrls() {
        var policy = new StripeCheckoutRedirectPolicy(
                SUCCESS, CANCEL);

        assertDoesNotThrow(() -> policy.validate(SUCCESS, CANCEL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:5173/payment/success",
            "http://127.0.0.1:5173/payment/success"
    })
    void acceptsExplicitlyConfiguredLocalDevelopmentUrl(String url) {
        var policy = new StripeCheckoutRedirectPolicy(url, CANCEL);

        assertDoesNotThrow(() -> policy.validate(url, CANCEL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/payment/success",
            "http://localhost.evil.example/success",
            "http://127.0.0.1.evil.example/success",
            "javascript:alert(1)",
            "//example.com/success",
            "/payment/success",
            "https://",
            "https://example.com/invalid path",
            "https://example.com@evil.example/success",
            "https://example.com/success#fragment",
            "https://example.com:65536/success",
            "https://example.com:0/success"
    })
    void rejectsUnsafeConfiguredSuccessUrl(String url) {
        assertThrows(IllegalStateException.class,
                () -> new StripeCheckoutRedirectPolicy(url, CANCEL));
    }

    @Test
    void rejectsUnsafeConfiguredCancelUrl() {
        assertThrows(IllegalStateException.class,
                () -> new StripeCheckoutRedirectPolicy(
                        SUCCESS, "http://example.com/cancel"));
    }

    @Test
    void rejectsMissingSuccessConfiguration() {
        var policy = new StripeCheckoutRedirectPolicy("", CANCEL);

        assertThrows(IllegalStateException.class,
                () -> policy.validate(SUCCESS, CANCEL));
    }

    @Test
    void rejectsMissingCancelConfiguration() {
        var policy = new StripeCheckoutRedirectPolicy(SUCCESS, "");

        assertThrows(IllegalStateException.class,
                () -> policy.validate(SUCCESS, CANCEL));
    }

    @Test
    void rejectsNullConfiguration() {
        var policy = new StripeCheckoutRedirectPolicy(null, null);

        assertThrows(IllegalStateException.class,
                () -> policy.validate(SUCCESS, CANCEL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/payment/success",
            "https://example.com.evil.example/payment/success",
            "https://example.com/payment/success/",
            "https://example.com/payment/success?next=https://evil.example",
            "https://example.com/payment/success#fragment",
            "https://example.com:443/payment/success",
            "http://example.com/payment/success",
            "http://localhost:5173/payment/success"
    })
    void rejectsUnapprovedDestination(String url) {
        var policy = new StripeCheckoutRedirectPolicy(
                SUCCESS, CANCEL);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(url, CANCEL));
    }

    @Test
    void rejectsSwappedSuccessAndCancelUrls() {
        var policy = new StripeCheckoutRedirectPolicy(
                SUCCESS, CANCEL);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(CANCEL, SUCCESS));
    }

    @Test
    void rejectsNullRequestedUrls() {
        var policy = new StripeCheckoutRedirectPolicy(
                SUCCESS, CANCEL);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(null, CANCEL));

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(SUCCESS, null));
    }
}