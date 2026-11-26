
package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for refund operation initialization,
 * input validation and lifecycle metadata.
 *
 * These tests do not contact MongoDB or a payment gateway.
 */
class RefundOperationTest {

    private RefundOperation createRefund() {
        return new RefundOperation(
                "dispute-001",
                "payment-001",
                "booking-001",
                "dispute-001",
                new BigDecimal("5000.00"),
                "LKR");
    }

    @Test
    void shouldCreateValidRefundOperation() {
        RefundOperation refund = createRefund();

        assertEquals("dispute-001", refund.getIdempotencyKey());
        assertEquals("payment-001", refund.getPaymentId());
        assertEquals("booking-001", refund.getBookingId());
        assertEquals("dispute-001", refund.getDisputeId());
        assertEquals(new BigDecimal("5000.00"), refund.getAmount());
        assertEquals("LKR", refund.getCurrency());
    }

    @Test
    void shouldInitializeWithPendingStatus() {
        RefundOperation refund = createRefund();

        assertEquals(
                RefundOperation.RefundStatus.PENDING,
                refund.getStatus());

        assertNotNull(refund.getCreatedAt());
        assertEquals(refund.getCreatedAt(), refund.getUpdatedAt());
        assertNull(refund.getCompletedAt());
        assertNull(refund.getGatewayPaymentReference());
        assertNull(refund.getGatewayRefundReference());
    }

    @Test
    void shouldRejectNullAmount() {
        assertThrows(NullPointerException.class, () -> new RefundOperation(
                "key", "payment", "booking",
                null, null, "LKR"));
    }

    @Test
    void shouldRejectZeroAmount() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                "key", "payment", "booking",
                null, BigDecimal.ZERO, "LKR"));
    }

    @Test
    void shouldRejectNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                "key", "payment", "booking",
                null, new BigDecimal("-100.00"), "LKR"));
    }

    @Test
    void shouldRejectExcessDecimalPrecision() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                "key", "payment", "booking",
                null, new BigDecimal("100.123"), "LKR"));
    }

    @Test
    void shouldNormalizeAmountScale() {
        RefundOperation refund = new RefundOperation(
                "key", "payment", "booking",
                null, new BigDecimal("100"), "LKR");

        assertEquals(new BigDecimal("100.00"), refund.getAmount());
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                null, "payment", "booking",
                null, BigDecimal.TEN, "LKR"));
    }

    @Test
    void shouldRejectBlankIdempotencyKey() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                " ", "payment", "booking",
                null, BigDecimal.TEN, "LKR"));
    }

    @Test
    void shouldRejectMissingPaymentId() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                "key", null, "booking",
                null, BigDecimal.TEN, "LKR"));
    }

    @Test
    void shouldRejectMissingBookingId() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                "key", "payment", null,
                null, BigDecimal.TEN, "LKR"));
    }

    @Test
    void shouldRejectMissingCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new RefundOperation(
                "key", "payment", "booking",
                null, BigDecimal.TEN, null));
    }

    @Test
    void shouldNormalizeCurrency() {
        RefundOperation refund = new RefundOperation(
                "key", "payment", "booking",
                null, BigDecimal.TEN, " lkr ");

        assertEquals("LKR", refund.getCurrency());
    }

    @Test
    void shouldAllowRefundWithoutDispute() {
        RefundOperation refund = new RefundOperation(
                "key", "payment", "booking",
                null, BigDecimal.TEN, "LKR");

        assertNull(refund.getDisputeId());
    }

    @Test
    void shouldNormalizeBlankDisputeIdToNull() {
        RefundOperation refund = new RefundOperation(
                "key", "payment", "booking",
                " ", BigDecimal.TEN, "LKR");

        assertNull(refund.getDisputeId());
    }

    @Test
    void shouldStoreGatewayPaymentReference() {
        RefundOperation refund = createRefund();

        refund.setGatewayPaymentReference("pi_test_001");

        assertEquals(
                "pi_test_001",
                refund.getGatewayPaymentReference());
    }

    @Test
    void shouldStoreGatewayRefundReference() {
        RefundOperation refund = createRefund();

        refund.setGatewayRefundReference("re_test_001");

        assertEquals(
                "re_test_001",
                refund.getGatewayRefundReference());
    }

    @Test
    void shouldRejectBlankGatewayPaymentReference() {
        RefundOperation refund = createRefund();

        assertThrows(IllegalArgumentException.class, () -> refund.setGatewayPaymentReference(" "));
    }

    @Test
    void shouldRejectBlankGatewayRefundReference() {
        RefundOperation refund = createRefund();

        assertThrows(IllegalArgumentException.class, () -> refund.setGatewayRefundReference(" "));
    }

    @Test
    void shouldUpdateProcessingStatus() {
        RefundOperation refund = createRefund();

        refund.setStatus(
                RefundOperation.RefundStatus.PROCESSING);

        assertEquals(
                RefundOperation.RefundStatus.PROCESSING,
                refund.getStatus());

        assertNull(refund.getCompletedAt());

        assertFalse(
                refund.getUpdatedAt().isBefore(refund.getCreatedAt()));
    }

    @Test
    void shouldRecordCompletionTimestampOnSuccess() {
        RefundOperation refund = createRefund();

        refund.setStatus(
                RefundOperation.RefundStatus.SUCCEEDED);

        assertEquals(
                RefundOperation.RefundStatus.SUCCEEDED,
                refund.getStatus());

        assertNotNull(refund.getCompletedAt());
        assertEquals(refund.getUpdatedAt(), refund.getCompletedAt());
    }

    @Test
    void shouldSupportReconciliationStatus() {
        RefundOperation refund = createRefund();

        refund.setStatus(
                RefundOperation.RefundStatus.REQUIRES_RECONCILIATION);

        assertEquals(
                RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                refund.getStatus());

        assertNull(refund.getCompletedAt());
    }

    @Test
    void shouldRejectNullStatus() {
        RefundOperation refund = createRefund();

        assertThrows(NullPointerException.class, () -> refund.setStatus(null));
    }
}
