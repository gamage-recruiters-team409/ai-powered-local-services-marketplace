package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundResult;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundStatus;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Stripe refund gateway adapter.
 *
 * <p>
 * Verifies request construction, financial precision,
 * idempotency, response validation, status mapping,
 * and uncertain gateway outcome handling.
 * </p>
 *
 * <p>
 * All Stripe API calls are mocked. No live gateway
 * requests or actual financial transactions are performed.
 * </p>
 */
class StripeRefundGatewayTest {

    private static final String SECRET_KEY = "sk_test_mock";
    private static final String WEBHOOK_SECRET = "whsec_mock";

    private static final String OPERATION_ID = "refund-operation-001";
    private static final String PAYMENT_INTENT_ID = "pi_test123";
    private static final String REFUND_ID = "re_test123";

    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("25.50");

    private static final String CURRENCY = "LKR";

    private StripeRefundGateway.StripeRefundCreator refundCreator;

    private StripeRefundGateway gateway;

    @BeforeEach
    void setUp() {

        refundCreator = mock(
                StripeRefundGateway.StripeRefundCreator.class);

        StripeGatewayProperties properties = new StripeGatewayProperties(
                true,
                SECRET_KEY,
                WEBHOOK_SECRET);

        gateway = new StripeRefundGateway(
                properties,
                refundCreator);
    }

    // =========================================================
    // Refund request construction
    // =========================================================

    @Test
    void createsRefundWithExpectedRequestParameters()
            throws StripeException {

        stubSuccessfulRefund();

        RefundResult result = executeRefund();

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);

        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);

        verify(refundCreator).create(
                eq(SECRET_KEY),
                paramsCaptor.capture(),
                optionsCaptor.capture());

        RefundCreateParams params = paramsCaptor.getValue();

        assertEquals(
                PAYMENT_INTENT_ID,
                params.getPaymentIntent());

        assertEquals(
                Long.valueOf(2550L),
                params.getAmount());

        assertEquals(
                java.util.Map.of("refundOperationId", OPERATION_ID),
                params.getMetadata());

        assertEquals(
                "wp07-refund-" + OPERATION_ID,
                optionsCaptor.getValue().getIdempotencyKey());

        assertEquals(
                REFUND_ID,
                result.gatewayRefundReference());

        assertEquals(
                PAYMENT_INTENT_ID,
                result.gatewayPaymentReference());

        assertEquals(REFUND_AMOUNT, result.amount());
        assertEquals(CURRENCY, result.currency());
        assertEquals(RefundStatus.SUCCEEDED, result.status());

        verifyNoMoreInteractions(refundCreator);
    }

    @ParameterizedTest
    @CsvSource({
            "1000.00, 100000",
            "250.50, 25050",
            "1.00, 100",
            "0.01, 1",
            "25.5000, 2550"
    })
    void sendsExplicitRefundAmountWithoutRounding(
            String amount,
            long expectedMinorUnits)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                expectedMinorUnits,
                "lkr",
                "succeeded"));

        RefundResult result = gateway.createRefund(
                OPERATION_ID,
                PAYMENT_INTENT_ID,
                new BigDecimal(amount),
                CURRENCY);

        ArgumentCaptor<RefundCreateParams> captor = ArgumentCaptor.forClass(RefundCreateParams.class);

        verify(refundCreator).create(
                eq(SECRET_KEY),
                captor.capture(),
                any(RequestOptions.class));

        assertEquals(
                Long.valueOf(expectedMinorUnits),
                captor.getValue().getAmount());

        assertEquals(
                BigDecimal.valueOf(expectedMinorUnits, 2),
                result.amount());

        assertEquals(2, result.amount().scale());
    }

    @ParameterizedTest
    @CsvSource({
            "USD, usd",
            "EUR, eur",
            "GBP, gbp",
            "LKR, lkr",
            "lkr, lkr",
            "usd, usd"
    })
    void acceptsAndNormalizesSupportedCurrencies(
            String requestedCurrency,
            String responseCurrency)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                2550L,
                responseCurrency,
                "succeeded"));

        RefundResult result = gateway.createRefund(
                OPERATION_ID,
                PAYMENT_INTENT_ID,
                REFUND_AMOUNT,
                requestedCurrency);

        assertEquals(
                responseCurrency.toUpperCase(java.util.Locale.ROOT),
                result.currency());

        assertEquals(RefundStatus.SUCCEEDED, result.status());
    }

    @Test
    void trimsInputValuesBeforeConstructingRequest()
            throws StripeException {

        stubSuccessfulRefund();

        RefundResult result = gateway.createRefund(
                "  " + OPERATION_ID + "  ",
                "  " + PAYMENT_INTENT_ID + "  ",
                REFUND_AMOUNT,
                "  lkr  ");

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);

        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);

        verify(refundCreator).create(
                eq(SECRET_KEY),
                paramsCaptor.capture(),
                optionsCaptor.capture());

        assertEquals(
                PAYMENT_INTENT_ID,
                paramsCaptor.getValue().getPaymentIntent());

        assertEquals(
                java.util.Map.of("refundOperationId", OPERATION_ID),
                paramsCaptor.getValue().getMetadata());

        assertEquals(
                "wp07-refund-" + OPERATION_ID,
                optionsCaptor.getValue().getIdempotencyKey());

        assertEquals(CURRENCY, result.currency());
    }

    // =========================================================
    // Gateway configuration and input validation
    // =========================================================

    @Test
    void rejectsRefundWhenStripeIsDisabled() {

        StripeGatewayProperties disabledProperties = new StripeGatewayProperties(false, "", "");

        StripeRefundGateway disabledGateway = new StripeRefundGateway(
                disabledProperties,
                refundCreator);

        assertThrows(
                IllegalStateException.class,
                () -> disabledGateway.createRefund(
                        OPERATION_ID,
                        PAYMENT_INTENT_ID,
                        REFUND_AMOUNT,
                        CURRENCY));

        verifyNoInteractions(refundCreator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   ", "\t" })
    void rejectsMissingRefundOperationId(String operationId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.createRefund(
                        operationId,
                        PAYMENT_INTENT_ID,
                        REFUND_AMOUNT,
                        CURRENCY));

        verifyNoInteractions(refundCreator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "pi_",
            "invalid",
            "cs_test123",
            "ch_test123",
            "re_test123",
            "pi_test-123",
            "pi_test 123",
            "pi_test_123"
    })
    void rejectsInvalidPaymentIntentReference(
            String paymentIntentId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.createRefund(
                        OPERATION_ID,
                        paymentIntentId,
                        REFUND_AMOUNT,
                        CURRENCY));

        verifyNoInteractions(refundCreator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "JPY",
            "INR",
            "AUD",
            "BTC",
            "US",
            "INVALID"
    })
    void rejectsMissingOrUnsupportedCurrency(String currency) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.createRefund(
                        OPERATION_ID,
                        PAYMENT_INTENT_ID,
                        REFUND_AMOUNT,
                        currency));

        verifyNoInteractions(refundCreator);
    }

    @Test
    void rejectsNullRefundAmount() {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.createRefund(
                        OPERATION_ID,
                        PAYMENT_INTENT_ID,
                        null,
                        CURRENCY));

        verifyNoInteractions(refundCreator);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "0.00",
            "-0.01",
            "-100.00",
            "0.001",
            "10.001",
            "25.555",
            "92233720368547758.08",
            "999999999999999999999999.99"
    })
    void rejectsInvalidRefundAmounts(String amount) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.createRefund(
                        OPERATION_ID,
                        PAYMENT_INTENT_ID,
                        new BigDecimal(amount),
                        CURRENCY));

        verifyNoInteractions(refundCreator);
    }

    // =========================================================
    // Stable refund idempotency
    // =========================================================

    @Test
    void reusesSameIdempotencyKeyForSameRefundOperation()
            throws StripeException {

        stubSuccessfulRefund();

        RefundResult firstResult = executeRefund();
        RefundResult secondResult = executeRefund();

        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);

        verify(refundCreator, times(2)).create(
                eq(SECRET_KEY),
                paramsCaptor.capture(),
                optionsCaptor.capture());

        RequestOptions firstOptions = optionsCaptor.getAllValues().get(0);

        RequestOptions secondOptions = optionsCaptor.getAllValues().get(1);

        assertEquals(
                "wp07-refund-" + OPERATION_ID,
                firstOptions.getIdempotencyKey());

        assertEquals(
                firstOptions.getIdempotencyKey(),
                secondOptions.getIdempotencyKey());

        RefundCreateParams firstParams = paramsCaptor.getAllValues().get(0);

        RefundCreateParams secondParams = paramsCaptor.getAllValues().get(1);

        assertEquals(
                firstParams.getPaymentIntent(),
                secondParams.getPaymentIntent());

        assertEquals(
                firstParams.getAmount(),
                secondParams.getAmount());

        assertEquals(
                firstParams.getMetadata(),
                secondParams.getMetadata());

        assertEquals(firstResult, secondResult);
    }

    @Test
    void usesDifferentIdempotencyKeysForDifferentOperations()
            throws StripeException {

        Refund firstRefund = createRefundResponse(
                "re_first123",
                PAYMENT_INTENT_ID,
                2550L,
                "lkr",
                "succeeded");

        Refund secondRefund = createRefundResponse(
                "re_second123",
                PAYMENT_INTENT_ID,
                2550L,
                "lkr",
                "succeeded");

        when(refundCreator.create(
                anyString(),
                any(RefundCreateParams.class),
                any(RequestOptions.class)))
                .thenReturn(firstRefund, secondRefund);

        RefundResult firstResult = gateway.createRefund(
                "operation-001",
                PAYMENT_INTENT_ID,
                REFUND_AMOUNT,
                CURRENCY);

        RefundResult secondResult = gateway.createRefund(
                "operation-002",
                PAYMENT_INTENT_ID,
                REFUND_AMOUNT,
                CURRENCY);

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);

        verify(refundCreator, times(2)).create(
                eq(SECRET_KEY),
                any(RefundCreateParams.class),
                captor.capture());

        assertEquals(
                "wp07-refund-operation-001",
                captor.getAllValues().get(0).getIdempotencyKey());

        assertEquals(
                "wp07-refund-operation-002",
                captor.getAllValues().get(1).getIdempotencyKey());

        assertNotEquals(
                captor.getAllValues().get(0).getIdempotencyKey(),
                captor.getAllValues().get(1).getIdempotencyKey());

        assertNotEquals(
                firstResult.gatewayRefundReference(),
                secondResult.gatewayRefundReference());
    }

    @Test
    void acceptsMaximumAllowedIdempotencyKeyLength()
            throws StripeException {

        stubSuccessfulRefund();

        String operationId = "a".repeat(
                255 - "wp07-refund-".length());

        gateway.createRefund(
                operationId,
                PAYMENT_INTENT_ID,
                REFUND_AMOUNT,
                CURRENCY);

        ArgumentCaptor<RequestOptions> captor = ArgumentCaptor.forClass(RequestOptions.class);

        verify(refundCreator).create(
                eq(SECRET_KEY),
                any(RefundCreateParams.class),
                captor.capture());

        assertEquals(
                255,
                captor.getValue().getIdempotencyKey().length());
    }

    @Test
    void rejectsOversizedIdempotencyKeyBeforeGatewayCall() {

        String operationId = "a".repeat(
                256 - "wp07-refund-".length());

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.createRefund(
                        operationId,
                        PAYMENT_INTENT_ID,
                        REFUND_AMOUNT,
                        CURRENCY));

        verifyNoInteractions(refundCreator);
    }

    // =========================================================
    // Gateway response validation
    // =========================================================

    @Test
    void rejectsNullGatewayResponse() throws StripeException {

        stubRefund(null);

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "re_",
            "invalid",
            "pi_test123",
            "re_test-123",
            "re_test 123"
    })
    void rejectsInvalidGatewayRefundReference(String refundId)
            throws StripeException {

        stubRefund(createRefundResponse(
                refundId,
                PAYMENT_INTENT_ID,
                2550L,
                "lkr",
                "succeeded"));

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "pi_other123",
            "ch_test123"
    })
    void rejectsMissingOrMismatchedPaymentIntent(
            String responsePaymentIntent)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                responsePaymentIntent,
                2550L,
                "lkr",
                "succeeded"));

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    @Test
    void rejectsMissingResponseAmount()
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                null,
                "lkr",
                "succeeded"));

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    @ParameterizedTest
    @ValueSource(longs = {
            -1L,
            0L,
            1L,
            2549L,
            2551L,
            100000L
    })
    void rejectsMismatchedResponseAmount(long responseAmount)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                responseAmount,
                "lkr",
                "succeeded"));

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "usd",
            "eur",
            "jpy",
            "invalid"
    })
    void rejectsMissingOrMismatchedResponseCurrency(
            String responseCurrency)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                2550L,
                responseCurrency,
                "succeeded"));

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    @Test
    void acceptsMatchingCurrencyRegardlessOfCase()
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                2550L,
                "LkR",
                "succeeded"));

        RefundResult result = executeRefund();

        assertEquals(CURRENCY, result.currency());
        assertEquals(RefundStatus.SUCCEEDED, result.status());

        verifySingleGatewayCall();
    }

    // =========================================================
    // Gateway refund status mapping
    // =========================================================

    @ParameterizedTest
    @CsvSource({
            "succeeded, SUCCEEDED",
            "pending, PENDING",
            "failed, FAILED",
            "canceled, CANCELED",
            "requires_action, REQUIRES_ACTION"
    })
    void mapsRecognizedStripeRefundStatuses(
            String stripeStatus,
            RefundStatus expectedStatus)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                2550L,
                "lkr",
                stripeStatus));

        RefundResult result = executeRefund();

        assertEquals(expectedStatus, result.status());

        assertEquals(
                REFUND_ID,
                result.gatewayRefundReference());

        assertEquals(REFUND_AMOUNT, result.amount());

        verifySingleGatewayCall();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "processing",
            "unexpected_status",
            "SUCCEEDED",
            "success"
    })
    void mapsUnrecognizedStatusesToUnknown(String stripeStatus)
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                2550L,
                "lkr",
                stripeStatus));

        RefundResult result = executeRefund();

        assertEquals(RefundStatus.UNKNOWN, result.status());

        assertNotEquals(RefundStatus.SUCCEEDED, result.status());
        assertNotEquals(RefundStatus.FAILED, result.status());

        verifySingleGatewayCall();
    }

    // =========================================================
    // Uncertain gateway outcomes
    // =========================================================

    @Test
    void treatsStripeExceptionAsUnconfirmedRefundOutcome()
            throws StripeException {

        StripeException stripeException = mock(StripeException.class);

        when(refundCreator.create(
                anyString(),
                any(RefundCreateParams.class),
                any(RequestOptions.class)))
                .thenThrow(stripeException);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        assertEquals(
                "Stripe refund outcome could not be confirmed.",
                exception.getMessage());

        // The adapter must not initiate another gateway call.
        verifySingleGatewayCall();
    }

    @Test
    void doesNotExposeSensitiveGatewayExceptionDetails()
            throws StripeException {

        StripeException stripeException = mock(StripeException.class);

        when(stripeException.getMessage())
                .thenReturn(
                        "Sensitive gateway response: " + SECRET_KEY);

        when(refundCreator.create(
                anyString(),
                any(RefundCreateParams.class),
                any(RequestOptions.class)))
                .thenThrow(stripeException);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        assertFalse(
                exception.getMessage().contains(SECRET_KEY));

        assertFalse(
                exception.getMessage()
                        .contains("Sensitive gateway response"));

        assertNull(exception.getCause());

        verifySingleGatewayCall();
    }

    @Test
    void rejectsInvalidResponseEvenWhenStatusIsSucceeded()
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                "pi_other123",
                2550L,
                "lkr",
                "succeeded"));

        assertThrows(
                IllegalStateException.class,
                this::executeRefund);

        verifySingleGatewayCall();
    }

    // =========================================================
    // Test helpers
    // =========================================================

    /**
     * Executes a refund using the standard trusted test inputs.
     */
    private RefundResult executeRefund() {

        return gateway.createRefund(
                OPERATION_ID,
                PAYMENT_INTENT_ID,
                REFUND_AMOUNT,
                CURRENCY);
    }

    /**
     * Configures a valid successful Stripe refund response.
     */
    private void stubSuccessfulRefund()
            throws StripeException {

        stubRefund(createRefundResponse(
                REFUND_ID,
                PAYMENT_INTENT_ID,
                2550L,
                "lkr",
                "succeeded"));
    }

    /**
     * Configures the mocked Stripe API response.
     */
    private void stubRefund(Refund refund)
            throws StripeException {

        when(refundCreator.create(
                anyString(),
                any(RefundCreateParams.class),
                any(RequestOptions.class)))
                .thenReturn(refund);
    }

    /**
     * Builds a mocked Stripe refund response without
     * communicating with the external payment gateway.
     */
    private Refund createRefundResponse(
            String refundId,
            String paymentIntentId,
            Long amount,
            String currency,
            String status) {

        Refund refund = mock(Refund.class);

        when(refund.getId()).thenReturn(refundId);
        when(refund.getPaymentIntent()).thenReturn(paymentIntentId);
        when(refund.getAmount()).thenReturn(amount);
        when(refund.getCurrency()).thenReturn(currency);
        when(refund.getStatus()).thenReturn(status);

        return refund;
    }

    /**
     * Ensures the adapter invokes Stripe exactly once
     * without performing an additional application-level retry.
     */
    private void verifySingleGatewayCall()
            throws StripeException {

        verify(refundCreator, times(1)).create(
                eq(SECRET_KEY),
                any(RefundCreateParams.class),
                any(RequestOptions.class));

        verifyNoMoreInteractions(refundCreator);
    }
}