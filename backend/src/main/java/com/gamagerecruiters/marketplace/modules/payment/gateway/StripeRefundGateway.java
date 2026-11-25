package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import com.stripe.model.StripeCollection;
import com.stripe.param.RefundListParams;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of the gateway-independent refund contract.
 *
 * <p>
 * Supports full and partial refunds against a verified Stripe
 * PaymentIntent reference using a stable idempotency key.
 * </p>
 *
 * <p>
 * This adapter does not modify local payment records, refund
 * operation states, booking states, commission amounts, earnings
 * ledger entries, or payout balances.
 * </p>
 *
 * <p>
 * Uncertain gateway outcomes must be reconciled before another
 * financial operation is attempted.
 * </p>
 */
@Component
public final class StripeRefundGateway implements PaymentRefundGateway {

    private static final String IDEMPOTENCY_KEY_PREFIX = "wp07-refund-";

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    /**
     * Supported two-decimal currencies, consistent with the
     * existing Stripe checkout implementation.
     */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "GBP", "LKR");

    private final StripeGatewayProperties properties;
    private final StripeRefundCreator refundCreator;
    private final StripeRefundRetriever refundRetriever;
    private final StripeRefundLister refundLister;

    /**
     * Production constructor using the configured Stripe client.
     */
    @Autowired
    public StripeRefundGateway(
            StripeGatewayProperties properties) {

        this(properties, (secretKey, params, options) -> new StripeClient(secretKey)
                .refunds()
                .create(params, options));
    }

    /**
     * Package-private constructor for isolated unit testing
     * without making live Stripe API requests.
     */
    StripeRefundGateway(
            StripeGatewayProperties properties,
            StripeRefundCreator refundCreator) {

        this(
                properties,
                refundCreator,
                (secretKey, reference) -> new StripeClient(secretKey)
                        .refunds().retrieve(reference),
                (secretKey, params) -> new StripeClient(secretKey)
                        .refunds().list(params));
    }

    /**
     * Allows isolated testing of refund creation, retrieval and listing
     * without contacting the external Stripe API.
     */
    StripeRefundGateway(
            StripeGatewayProperties properties,
            StripeRefundCreator refundCreator,
            StripeRefundRetriever refundRetriever,
            StripeRefundLister refundLister) {

        this.properties = Objects.requireNonNull(properties);
        this.refundCreator = Objects.requireNonNull(refundCreator);
        this.refundRetriever = Objects.requireNonNull(refundRetriever);
        this.refundLister = Objects.requireNonNull(refundLister);
    }

    /**
     * Isolates the external Stripe API invocation.
     */
    @FunctionalInterface
    interface StripeRefundCreator {

        Refund create(
                String secretKey,
                RefundCreateParams params,
                RequestOptions options) throws StripeException;
    }

    /**
     * Isolates read-only retrieval of a known Stripe refund.
     */
    @FunctionalInterface
    interface StripeRefundRetriever {

        Refund retrieve(
                String secretKey,
                String reference) throws StripeException;
    }

    /**
     * Isolates paginated read-only Stripe refund listing.
     */
    @FunctionalInterface
    interface StripeRefundLister {

        StripeCollection<Refund> list(
                String secretKey,
                RefundListParams params) throws StripeException;
    }

    /**
     * Creates a Stripe refund using a persisted refund operation ID
     * and a verified PaymentIntent reference.
     */
    @Override
    public RefundResult createRefund(
            String refundOperationId,
            String gatewayPaymentReference,
            BigDecimal amount,
            String currency) {

        // Fail closed when Stripe integration is disabled.
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Stripe payment gateway is disabled.");
        }

        String operationId = requireText(
                refundOperationId, "Refund operation ID");

        String paymentIntentId = requireText(
                gatewayPaymentReference, "PaymentIntent ID");

        if (!paymentIntentId.matches("pi_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Invalid Stripe PaymentIntent ID.");
        }

        String normalizedCurrency = normalizeCurrency(currency);

        long amountInMinorUnits = toMinorUnits(amount);

        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + operationId;

        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {

            throw new IllegalArgumentException(
                    "Refund idempotency key exceeds the maximum length.");
        }

        /*
         * Always provide an explicit refund amount.
         *
         * Omitting the amount could cause Stripe to refund the
         * entire remaining refundable balance unintentionally.
         */
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setAmount(amountInMinorUnits)
                .putMetadata("refundOperationId", operationId)
                .build();

        /*
         * The same persisted refund operation must always reuse
         * this key with identical request parameters.
         */
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        try {
            Refund refund = refundCreator.create(
                    properties.getSecretKey(),
                    params,
                    options);

            return validateAndMapResponse(
                    refund,
                    paymentIntentId,
                    amountInMinorUnits,
                    normalizedCurrency);

        } catch (StripeException exception) {

            /*
             * A gateway exception does not establish whether the
             * refund was executed. Do not report a definite failure
             * or automatically create another refund operation.
             *
             * The orchestration layer must reconcile the outcome.
             * Avoid exposing raw gateway response details.
             */
            throw new IllegalStateException(
                    "Stripe refund outcome could not be confirmed.");
        }
    }

    /**
     * Locates an existing Stripe refund without submitting
     * another financial request.
     *
     * Missing or ambiguous results must not be interpreted
     * as definitive refund failures.
     */
    @Override
    public Optional<RefundResult> findRefund(
            String refundOperationId,
            String gatewayPaymentReference,
            String gatewayRefundReference,
            BigDecimal amount,
            String currency) {

        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Stripe payment gateway is disabled.");
        }

        String operationId = requireText(
                refundOperationId, "Refund operation ID");

        String paymentIntentId = requireText(
                gatewayPaymentReference, "PaymentIntent ID");

        if (!paymentIntentId.matches("pi_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Invalid Stripe PaymentIntent ID.");
        }

        String normalizedCurrency = normalizeCurrency(currency);
        long amountInMinorUnits = toMinorUnits(amount);



        try {

            // Prefer direct retrieval when the reference is known.
            if (gatewayRefundReference != null) {

                String reference = requireText(
                        gatewayRefundReference,
                        "Gateway refund reference");

                if (!reference.matches("re_[A-Za-z0-9]+")) {
                    throw new IllegalArgumentException(
                            "Invalid Stripe refund reference.");
                }

                Refund refund = refundRetriever.retrieve(
                        properties.getSecretKey(), reference);

                if (refund == null
                        || !reference.equals(refund.getId())) {

                    throw new IllegalStateException(
                            "Stripe refund reference mismatch.");
                }

                validateOperationMetadata(refund, operationId);

                return Optional.of(validateAndMapResponse(
                        refund,
                        paymentIntentId,
                        amountInMinorUnits,
                        normalizedCurrency));
            }

            // Recover a missing refund reference using metadata.
            return findRefundByOperationMetadata(
                    operationId,
                    paymentIntentId,
                    amountInMinorUnits,
                    normalizedCurrency);

        } catch (StripeException exception) {

            // Preserve reservations when lookup cannot be completed.
            throw new IllegalStateException(
                    "Stripe refund reconciliation could not be completed.");
        }
    }

    /**
     * Searches bounded pages of refunds belonging to the
     * verified PaymentIntent.
     *
     * Multiple matching refunds or incomplete pagination
     * require further investigation instead of settlement.
     */
    private Optional<RefundResult> findRefundByOperationMetadata(
            String operationId,
            String paymentIntentId,
            long amountInMinorUnits,
            String currency) throws StripeException {

        final int maximumPages = 20;

        String cursor = null;
        RefundResult matchedResult = null;

        for (int page = 0; page < maximumPages; page++) {

            RefundListParams.Builder builder =
                    RefundListParams.builder()
                            .setPaymentIntent(paymentIntentId)
                            .setLimit(100L);

            if (cursor != null) {
                builder.setStartingAfter(cursor);
            }

            StripeCollection<Refund> refunds =
                    refundLister.list(
                            properties.getSecretKey(), builder.build());

            if (refunds == null
                    || refunds.getData() == null
                    || refunds.getHasMore() == null) {

                throw new IllegalStateException(
                        "Stripe returned an incomplete refund list.");
            }

            for (Refund refund : refunds.getData()) {

                if (refund == null) {
                    throw new IllegalStateException(
                            "Stripe returned an invalid refund entry.");
                }

                if (refund.getMetadata() == null
                        || !operationId.equals(
                                refund.getMetadata()
                                        .get("refundOperationId"))) {

                    continue;
                }

                RefundResult candidate = validateAndMapResponse(
                        refund,
                        paymentIntentId,
                        amountInMinorUnits,
                        currency);

                if (matchedResult != null) {

                    throw new IllegalStateException(
                            "Multiple Stripe refunds match the same operation.");
                }

                matchedResult = candidate;
            }

            if (!Boolean.TRUE.equals(refunds.getHasMore())) {
                return Optional.ofNullable(matchedResult);
            }

            if (refunds.getData().isEmpty()) {

                throw new IllegalStateException(
                        "Stripe refund pagination returned an empty page.");
            }

            Refund lastRefund = refunds.getData().get(
                    refunds.getData().size() - 1);

            String nextCursor = requireText(
                    lastRefund.getId(), "Refund pagination cursor");

            if (!nextCursor.matches("re_[A-Za-z0-9]+")
                    || nextCursor.equals(cursor)) {

                throw new IllegalStateException(
                        "Stripe refund pagination did not advance.");
            }

            cursor = nextCursor;
        }

        // Never accept a partial search as a complete lookup.
        throw new IllegalStateException(
                "Stripe refund reconciliation search limit exceeded.");
    }

    /**
     * Confirms that the gateway refund belongs to the exact
     * persisted internal refund operation.
     */
    private static void validateOperationMetadata(
            Refund refund,
            String operationId) {

        if (refund == null
                || refund.getMetadata() == null
                || !operationId.equals(
                        refund.getMetadata().get("refundOperationId"))) {

            throw new IllegalStateException(
                    "Stripe refund operation metadata mismatch.");
        }
    }


    /**
     * Validates the gateway response before returning it to the
     * refund orchestration layer.
     */
    private static RefundResult validateAndMapResponse(
            Refund refund,
            String expectedPaymentIntentId,
            long expectedAmountInMinorUnits,
            String expectedCurrency) {

        if (refund == null) {
            throw new IllegalStateException(
                    "Stripe returned an empty refund response.");
        }

        String refundId = refund.getId();

        if (refundId == null
                || !refundId.matches("re_[A-Za-z0-9]+")) {

            throw new IllegalStateException(
                    "Stripe returned an invalid refund reference.");
        }

        // Never accept a refund associated with another payment.
        if (!expectedPaymentIntentId.equals(
                refund.getPaymentIntent())) {

            throw new IllegalStateException(
                    "Stripe refund PaymentIntent reference mismatch.");
        }

        // Protect against unexpected refund amounts.
        Long actualAmount = refund.getAmount();

        if (actualAmount == null
                || actualAmount.longValue() != expectedAmountInMinorUnits) {

            throw new IllegalStateException(
                    "Stripe refund amount mismatch.");
        }

        String actualCurrency = refund.getCurrency();

        if (actualCurrency == null
                || !expectedCurrency.equalsIgnoreCase(
                        actualCurrency)) {

            throw new IllegalStateException(
                    "Stripe refund currency mismatch.");
        }

        RefundStatus status = mapStatus(refund.getStatus());

        return new RefundResult(
                refundId,
                expectedPaymentIntentId,
                BigDecimal.valueOf(actualAmount, 2),
                expectedCurrency,
                status);
    }

    /**
     * Maps Stripe refund statuses to gateway-independent statuses.
     *
     * Unknown or missing statuses must never be treated as
     * successful or definitively failed refunds.
     */
    private static RefundStatus mapStatus(String status) {

        if (status == null) {
            return RefundStatus.UNKNOWN;
        }

        return switch (status) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "pending" -> RefundStatus.PENDING;
            case "failed" -> RefundStatus.FAILED;
            case "canceled" -> RefundStatus.CANCELED;
            case "requires_action" -> RefundStatus.REQUIRES_ACTION;
            default -> RefundStatus.UNKNOWN;
        };
    }

    /**
     * Validates and normalizes a supported refund currency.
     */
    private static String normalizeCurrency(String currency) {

        String normalizedCurrency = requireText(
                currency, "Currency")
                .toUpperCase(Locale.ROOT);

        if (!SUPPORTED_CURRENCIES.contains(
                normalizedCurrency)) {

            throw new IllegalArgumentException(
                    "Unsupported Stripe refund currency.");
        }

        return normalizedCurrency;
    }

    /**
     * Converts a positive major-unit amount into exact minor units.
     *
     * No rounding is permitted because it could alter the
     * authorized refund amount.
     */
    private static long toMinorUnits(BigDecimal amount) {

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Refund amount must be positive.");
        }

        final long amountInMinorUnits;

        try {
            amountInMinorUnits = amount.movePointRight(2).longValueExact();

        } catch (ArithmeticException exception) {

            throw new IllegalArgumentException(
                    "Refund amount cannot be represented in minor units.",
                    exception);
        }

        if (amountInMinorUnits <= 0) {
            throw new IllegalArgumentException(
                    "Refund amount is too small.");
        }

        return amountInMinorUnits;
    }

    /**
     * Requires a non-blank identifier or configuration value.
     */
    private static String requireText(
            String value,
            String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required.");
        }

        return value.trim();
    }
}