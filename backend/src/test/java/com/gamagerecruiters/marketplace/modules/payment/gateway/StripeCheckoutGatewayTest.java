package com.gamagerecruiters.marketplace.modules.payment.gateway;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;

/**
 * Tests checkout validation without making real Stripe API requests.
 *
 * <p>These tests cover disabled-gateway behavior and invalid requests.
 * Successful Stripe session creation requires a separately injectable
 * API boundary and will be covered by additional tests.</p>
 */
class StripeCheckoutGatewayTest {

    private StripeCheckoutGateway gateway;

    @BeforeEach
    void setUp() {
        StripeGatewayProperties properties =
                new StripeGatewayProperties(true, "sk_test_dummy", "whsec_dummy");
        gateway = new StripeCheckoutGateway(
                properties,
                (secretKey, params, options) -> {
                    throw new AssertionError(
                            "Invalid checkout input must not reach Stripe.");
                });
    }

    @Test
    void disabledGatewayRejectsCheckout() {
        StripeCheckoutGateway disabledGateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(false, "", ""));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> disabledGateway.createCheckoutSession(
                        "payment-1", new BigDecimal("100.00"),
                        "USD", "https://example.com/success",
                        "https://example.com/cancel"));

        assertTrue(exception.getMessage().contains("disabled"));
    }

    @Test
    void blankPaymentIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> createCheckout(" ", new BigDecimal("100.00"), "USD"));
    }

    @Test
    void nullAmountIsRejected() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout("payment-1", null, "USD"));
    }

    @Test
    void zeroAmountIsRejected() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout("payment-1", BigDecimal.ZERO, "USD"));
    }

    @Test
    void negativeAmountIsRejected() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout("payment-1", new BigDecimal("-1.00"), "USD"));
    }

    @Test
    void unsupportedCurrencyIsRejected() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout("payment-1", new BigDecimal("100.00"), "JPY"));
    }

    @Test
    void fractionalMinorUnitsAreRejected() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout("payment-1", new BigDecimal("10.001"), "USD"));
    }

    @Test
    void unsupportedAudCurrencyIsRejectedBeforeSubmission() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout(
                        "payment-1", new BigDecimal("100.00"), "AUD"));
    }

    @Test
    void minorUnitOverflowIsRejectedBeforeSubmission() {
        assertThrows(CheckoutNotSubmittedException.class,
                () -> createCheckout(
                        "payment-1",
                        new BigDecimal("92233720368547758.08"),
                        "USD"));
    }

    @Test
    void blankRedirectUrlIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> gateway.createCheckoutSession(
                        "payment-1", new BigDecimal("100.00"), "USD",
                        " ", "https://example.com/cancel"));
    }


    @Test
    void successfulCheckoutReturnsGatewaySession() {
        com.stripe.model.checkout.Session response =
                new com.stripe.model.checkout.Session();
        response.setId("cs_test_123");
        response.setUrl("https://checkout.stripe.com/test-session");

        StripeCheckoutGateway testGateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(true, "sk_test_dummy", "whsec_dummy"),
                (secretKey, params, options) -> response);

        PaymentCheckoutGateway.CheckoutSession result =
                testGateway.createCheckoutSession(
                        "payment-123", new BigDecimal("100.00"), "USD",
                        "https://example.com/success",
                        "https://example.com/cancel");

        org.junit.jupiter.api.Assertions.assertEquals(
                "cs_test_123", result.sessionId());
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://checkout.stripe.com/test-session",
                result.checkoutUrl());
    }

    @Test
    void checkoutUsesTrustedAmountAndStableIdempotencyKey() {
        java.util.concurrent.atomic.AtomicReference<
                com.stripe.param.checkout.SessionCreateParams> capturedParams =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<
                com.stripe.net.RequestOptions> capturedOptions =
                new java.util.concurrent.atomic.AtomicReference<>();

        com.stripe.model.checkout.Session response =
                new com.stripe.model.checkout.Session();
        response.setId("cs_test_456");
        response.setUrl("https://checkout.stripe.com/test-session");

        StripeCheckoutGateway testGateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(true, "sk_test_dummy", "whsec_dummy"),
                (secretKey, params, options) -> {
                    capturedParams.set(params);
                    capturedOptions.set(options);
                    return response;
                });

        testGateway.createCheckoutSession(
                "payment-456", new BigDecimal("123.45"), "USD",
                "https://example.com/success",
                "https://example.com/cancel");

        org.junit.jupiter.api.Assertions.assertEquals(
                "wp07-checkout-payment-456",
                capturedOptions.get().getIdempotencyKey());
        org.junit.jupiter.api.Assertions.assertEquals(
                12345L,
                capturedParams.get().getLineItems().get(0)
                        .getPriceData().getUnitAmount());
        org.junit.jupiter.api.Assertions.assertEquals(
                "payment-456", capturedParams.get().getClientReferenceId());
    }

    @Test
    void incompleteStripeResponseIsRejected() {
        StripeCheckoutGateway testGateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(true, "sk_test_dummy", "whsec_dummy"),
                (secretKey, params, options) ->
                        new com.stripe.model.checkout.Session());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> testGateway.createCheckoutSession(
                        "payment-789", new BigDecimal("100.00"), "USD",
                        "https://example.com/success",
                        "https://example.com/cancel"));

        assertTrue(exception.getMessage().contains("incomplete"));
    }
    @Test
    void stripeApiFailureIsHandledWithoutExposingGatewayDetails() {
        StripeCheckoutGateway testGateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(true, "sk_test_dummy", "whsec_dummy"),
                (secretKey, params, options) -> {
                    throw new com.stripe.exception.InvalidRequestException(
                            "Sensitive Stripe error details",
                            "test-param",
                            "req_test_123",
                            null,
                            400,
                            null);
                });

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> testGateway.createCheckoutSession(
                        "payment-error", new BigDecimal("100.00"), "USD",
                        "https://example.com/success",
                        "https://example.com/cancel"));

        org.junit.jupiter.api.Assertions.assertEquals(
                "Unable to create Stripe checkout session.",
                exception.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(exception.getCause());
    }
    private void createCheckout(
            String paymentId,
            BigDecimal amount,
            String currency) {
        gateway.createCheckoutSession(
                paymentId,
                amount,
                currency,
                "https://example.com/success",
                "https://example.com/cancel");
    }
}