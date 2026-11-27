package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.StripeClient;
import com.stripe.model.StripeCollection;
import com.stripe.param.checkout.SessionListParams;
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
    private final StripeSessionLister sessionLister;
    private final StripeSessionRetriever sessionRetriever;

    /**
     * Currencies supported by the current two-decimal Checkout adapter.
     * Creation, recovery and retrieval share this authoritative rule.
     */
    private static final Set<String> SUPPORTED_CURRENCIES =
            Set.of("USD", "EUR", "GBP", "LKR");

    private static final int MAX_RECOVERY_PAGES = 20;
    private static final long RECOVERY_PAGE_SIZE = 100L;

    @Autowired
    public StripeCheckoutGateway(StripeGatewayProperties properties) {
        this(properties, (secretKey, params, options) ->
                new StripeClient(secretKey).checkout().sessions().create(params, options));
    }

    // Package-private constructor allows isolated tests without Stripe network calls.
    StripeCheckoutGateway(
            StripeGatewayProperties properties,
            StripeSessionCreator sessionCreator) {
        this(properties, sessionCreator, (secretKey, params) ->
                new StripeClient(secretKey).checkout().sessions().list(params));
    }

    // Injectable listing boundary for isolated recovery tests.
    StripeCheckoutGateway(
            StripeGatewayProperties properties,
            StripeSessionCreator sessionCreator,
            StripeSessionLister sessionLister) {

        this(properties, sessionCreator, sessionLister,
                (secretKey, sessionId) ->
                        new StripeClient(secretKey)
                                .checkout().sessions().retrieve(sessionId));
    }

    // Injectable retrieval boundary for isolated tests.
    StripeCheckoutGateway(
            StripeGatewayProperties properties,
            StripeSessionCreator sessionCreator,
            StripeSessionLister sessionLister,
            StripeSessionRetriever sessionRetriever) {

        this.properties = Objects.requireNonNull(properties);
        this.sessionCreator = Objects.requireNonNull(sessionCreator);
        this.sessionLister = Objects.requireNonNull(sessionLister);
        this.sessionRetriever = Objects.requireNonNull(sessionRetriever);
    }

    @FunctionalInterface
    interface StripeSessionCreator {
        Session create(
                String secretKey,
                SessionCreateParams params,
                RequestOptions options) throws StripeException;
    }

    /**
     * Checks the configured Checkout adapter's currency capability
     * without submitting a Stripe request.
     */
    @Override
    public boolean supportsCurrency(String currency) {

        return currency != null
                && !currency.isBlank()
                && SUPPORTED_CURRENCIES.contains(
                        currency.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public CheckoutSession createCheckoutSession(
            String paymentId,
            BigDecimal amount,
            String currency,
            String successUrl,
            String cancelUrl) {

        if (!properties.isEnabled()) {
            throw new CheckoutNotSubmittedException("Stripe payment gateway is disabled.");
        }

        requireText(paymentId, "Payment ID");
        requireText(currency, "Currency");
        requireText(successUrl, "Success URL");
        requireText(cancelUrl, "Cancel URL");

        // Monetary validation occurs before any external request is submitted.
        // Confirmed pre-submission failures permit token-checked reservation release.
        if (amount == null || amount.signum() <= 0) {
            throw new CheckoutNotSubmittedException("Payment amount must be positive.");
        }

        // This initial adapter supports only two-decimal currencies.
        // Currency-specific conversion rules must be extended before
        // supporting zero-decimal or other special-case currencies.
        String normalizedCurrency = currency.trim().toUpperCase(java.util.Locale.ROOT);

        if (!supportsCurrency(normalizedCurrency)) {
            throw new CheckoutNotSubmittedException(
                    "Unsupported Stripe checkout currency.");
        }

        final long amountInMinorUnits;
        try {
            amountInMinorUnits = amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new CheckoutNotSubmittedException(
                    "Payment amount cannot be represented in minor units.",
                    exception);
        }

        if (amountInMinorUnits <= 0) {
            throw new CheckoutNotSubmittedException("Payment amount is too small.");
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

    @FunctionalInterface
    interface StripeSessionRetriever {
        Session retrieve(
                String secretKey,
                String sessionId) throws StripeException;
    }


    @FunctionalInterface
    interface StripeSessionLister {
        StripeCollection<Session> list(
                String secretKey,
                SessionListParams params) throws StripeException;
    }

    /**
     * Searches existing Stripe sessions without creating a new session.
     * Incomplete or ambiguous searches fail closed.
     */
    @Override
    public Optional<RecoveredCheckoutSession> findCheckoutSession(
            String paymentId,
            BigDecimal amount,
            String currency,
            Instant reservedAt) {

        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Stripe checkout recovery is disabled.");
        }

        requireText(paymentId, "Payment ID");
        requireText(currency, "Currency");

        if (amount == null || amount.signum() <= 0
                || reservedAt == null
                || reservedAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException(
                    "Invalid checkout recovery parameters.");
        }

        String normalizedCurrency =
                currency.trim().toUpperCase(Locale.ROOT);

        if (!supportsCurrency(normalizedCurrency)) {
            throw new IllegalArgumentException(
                    "Unsupported checkout recovery currency.");
        }

        final long expectedMinorUnits;

        try {
            expectedMinorUnits =
                    amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Invalid checkout recovery amount.", exception);
        }

        // Include a small clock-skew allowance in the search window.
        long earliestCreated = Math.max(
                0L, reservedAt.getEpochSecond() - 300L);

        SessionListParams.Created created =
                SessionListParams.Created.builder()
                        .setGte(earliestCreated)
                        .build();

        Session matchingSession = null;
        String cursor = null;
        Set<String> seenSessionIds = new HashSet<>();

        try {
            for (int page = 0; page < MAX_RECOVERY_PAGES; page++) {

                SessionListParams.Builder builder =
                        SessionListParams.builder()
                                .setCreated(created)
                                .setLimit(RECOVERY_PAGE_SIZE);

                if (cursor != null) {
                    builder.setStartingAfter(cursor);
                }

                StripeCollection<Session> result =
                        sessionLister.list(
                                properties.getSecretKey(),
                                builder.build());

                if (result == null
                        || result.getData() == null
                        || result.getHasMore() == null) {
                    throw new IllegalStateException(
                            "Incomplete Stripe checkout recovery response.");
                }

                for (Session session : result.getData()) {

                    if (session == null
                            || session.getId() == null
                            || session.getId().isBlank()) {
                        throw new IllegalStateException(
                                "Invalid Stripe checkout search result.");
                    }

                    if (!seenSessionIds.add(session.getId())) {
                        throw new IllegalStateException(
                                "Repeated Stripe checkout search result.");
                    }

                    if (!paymentId.equals(
                            session.getClientReferenceId())) {
                        continue;
                    }

                    if (matchingSession != null) {
                        throw new IllegalStateException(
                                "Multiple Stripe sessions match the payment.");
                    }

                    validateRecoveredSession(
                            session,
                            expectedMinorUnits,
                            normalizedCurrency,
                            earliestCreated);

                    matchingSession = session;
                }

                if (!result.getHasMore()) {

                    if (matchingSession == null) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new RecoveredCheckoutSession(
                                    matchingSession.getId(),
                                    matchingSession.getUrl(),
                                    matchingSession.getStatus(),
                                    matchingSession.getPaymentStatus()));
                }

                if (result.getData().isEmpty()) {
                    throw new IllegalStateException(
                            "Invalid Stripe checkout pagination.");
                }

                cursor = result.getData()
                        .get(result.getData().size() - 1)
                        .getId();
            }

            throw new IllegalStateException(
                    "Stripe checkout recovery search limit exceeded.");

        } catch (StripeException exception) {
            throw new IllegalStateException(
                    "Unable to reconcile Stripe checkout session.",
                    exception);
        }
    }

    /**
     * Retrieves an existing Stripe Checkout Session using its persisted
     * gateway reference.
     *
     * <p>The returned session must belong to the expected internal
     * payment and match its trusted financial values. This operation
     * never creates a replacement session or confirms payment capture.</p>
     */
    @Override
    public RecoveredCheckoutSession retrieveCheckoutSession(
            String sessionId,
            String paymentId,
            BigDecimal amount,
            String currency) {

        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Stripe checkout retrieval is disabled.");
        }

        requireText(sessionId, "Checkout Session ID");
        requireText(paymentId, "Payment ID");
        requireText(currency, "Currency");

        if (!sessionId.startsWith("cs_")) {
            throw new IllegalArgumentException(
                    "Invalid Checkout Session reference.");
        }

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid checkout retrieval amount.");
        }

        String normalizedCurrency =
                currency.trim().toUpperCase(Locale.ROOT);

        if (!supportsCurrency(normalizedCurrency)) {

            throw new IllegalArgumentException(
                    "Unsupported checkout retrieval currency.");
        }

        final long expectedMinorUnits;

        try {
            expectedMinorUnits =
                    amount.movePointRight(2).longValueExact();

        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Invalid checkout retrieval amount.", exception);
        }

        if (expectedMinorUnits <= 0) {
            throw new IllegalArgumentException(
                    "Invalid checkout retrieval minor units.");
        }

        try {
            Session session = sessionRetriever.retrieve(
                    properties.getSecretKey(), sessionId);

            if (session == null
                    || !sessionId.equals(session.getId())
                    || !paymentId.equals(
                            session.getClientReferenceId())) {

                throw new IllegalStateException(
                        "Retrieved Stripe checkout identity mismatch.");
            }

            // Reuse existing financial and session-state validation.
            // Direct retrieval does not require a reservation search window.
            validateRecoveredSession(
                    session,
                    expectedMinorUnits,
                    normalizedCurrency,
                    0L);

            return new RecoveredCheckoutSession(
                    session.getId(),
                    "open".equals(session.getStatus())
                            ? session.getUrl() : null,
                    session.getStatus(),
                    session.getPaymentStatus());

        } catch (StripeException exception) {
            throw new IllegalStateException(
                    "Unable to retrieve Stripe checkout session.",
                    exception);
        }
    }

    /**
     * Validates financial identity and the supported session states.
     * Recovery never marks a payment as captured.
     */
    private static void validateRecoveredSession(
            Session session,
            long expectedMinorUnits,
            String expectedCurrency,
            long earliestCreated) {

        if (!session.getId().startsWith("cs_")
                || session.getAmountTotal() == null
                || session.getAmountTotal() != expectedMinorUnits
                || !expectedCurrency.equalsIgnoreCase(
                        session.getCurrency())
                || !"payment".equals(session.getMode())
                || session.getCreated() == null
                || session.getCreated() < earliestCreated) {

            throw new IllegalStateException(
                    "Recovered Stripe checkout details mismatch.");
        }

        String status = session.getStatus();
        String paymentStatus = session.getPaymentStatus();

        if ("open".equals(status)) {

            if (!"unpaid".equals(paymentStatus)
                    || session.getUrl() == null
                    || session.getUrl().isBlank()
                    || session.getExpiresAt() == null
                    || session.getExpiresAt()
                            <= Instant.now().getEpochSecond()) {

                throw new IllegalStateException(
                        "Recovered open checkout session is invalid.");
            }

            return;
        }

        if ("complete".equals(status)
                && ("paid".equals(paymentStatus)
                    || "unpaid".equals(paymentStatus))) {
            return;
        }

        throw new IllegalStateException(
                "Recovered checkout session requires manual reconciliation.");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
    }
}