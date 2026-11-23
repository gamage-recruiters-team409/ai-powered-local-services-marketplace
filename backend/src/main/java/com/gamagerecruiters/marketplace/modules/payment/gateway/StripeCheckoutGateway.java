package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of the hosted payment checkout gateway.
 *
 * <p>
 * This adapter creates Stripe Checkout Sessions only. It does not persist
 * payment records, confirm bookings, or process webhook notifications.
 * </p>
 */
@Component
public final class StripeCheckoutGateway implements PaymentCheckoutGateway {

    private final StripeGatewayProperties properties;
    private final StripeSessionCreator sessionCreator;

    @Autowired
    public StripeCheckoutGateway(StripeGatewayProperties properties) {
        this(properties, (secretKey, params, options) ->
                new StripeClient(secretKey).checkout().sessions().create(params, options));
    }

    // Package-private constructor allows isolated tests without Stripe network calls.
    StripeCheckoutGateway(
            StripeGatewayProperties properties,
            StripeSessionCreator sessionCreator) {
        this.properties = Objects.requireNonNull(properties);
        this.sessionCreator = Objects.requireNonNull(sessionCreator);
    }

    @FunctionalInterface
    interface StripeSessionCreator {
        Session create(
                String secretKey,
                SessionCreateParams params,
                RequestOptions options) throws StripeException;
    }

    @Override
    public CheckoutSession createCheckoutSession(
            String paymentId,
            BigDecimal amount,
            String currency,
            String successUrl,
            String cancelUrl) {

        if (!properties.isEnabled()) {
            throw new IllegalStateException("Stripe payment gateway is disabled.");
        }

        requireText(paymentId, "Payment ID");
        requireText(currency, "Currency");
        requireText(successUrl, "Success URL");
        requireText(cancelUrl, "Cancel URL");

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive.");
        }

        // This initial adapter supports only two-decimal currencies.
        // Currency-specific conversion rules must be extended before
        // supporting zero-decimal or other special-case currencies.
        String normalizedCurrency = currency.trim().toUpperCase(java.util.Locale.ROOT);

        if (!java.util.Set.of("USD", "EUR", "GBP", "LKR")
                .contains(normalizedCurrency)) {
            throw new IllegalArgumentException(
                    "Unsupported Stripe checkout currency.");
        }

        final long amountInMinorUnits;
        try {
            amountInMinorUnits = amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Payment amount cannot be represented in minor units.",
                    exception);
        }

        if (amountInMinorUnits <= 0) {
            throw new IllegalArgumentException("Payment amount is too small.");
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setClientReferenceId(paymentId)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPriceData(
                                        SessionCreateParams.LineItem.PriceData.builder()
                                                .setCurrency(normalizedCurrency.toLowerCase(
                                                        java.util.Locale.ROOT))
                                                .setUnitAmount(amountInMinorUnits)
                                                .setProductData(
                                                        SessionCreateParams.LineItem.PriceData.ProductData
                                                                .builder()
                                                                .setName("Marketplace booking payment")
                                                                .build())
                                                .build())
                                .build())
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("wp07-checkout-" + paymentId.trim())
                .build();

        try {
            Session session = sessionCreator.create(
                    properties.getSecretKey(), params, options);

            if (session == null
                    || session.getId() == null
                    || session.getId().isBlank()
                    || session.getUrl() == null
                    || session.getUrl().isBlank()) {
                throw new IllegalStateException(
                        "Stripe returned an incomplete checkout session.");
            }

            return new CheckoutSession(session.getId(), session.getUrl());

        } catch (StripeException exception) {
            // Do not expose gateway response bodies or credentials to callers.
            throw new IllegalStateException(
                    "Unable to create Stripe checkout session.",
                    exception);
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
    }
}