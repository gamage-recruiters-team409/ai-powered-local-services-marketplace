package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;

/**
 * Customer-visible representation of a persisted refund operation.
 *
 * <p>Internal gateway references, idempotency keys, dispute identifiers
 * and financial reservation details are intentionally excluded.</p>
 */
public record CustomerRefundResponse(
        String refundId,
        String paymentId,
        String bookingId,
        BigDecimal amount,
        String currency,
        CustomerRefundStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    /**
     * Stable customer-facing refund processing states.
     */
    public enum CustomerRefundStatus {
        PROCESSING,
        UNDER_REVIEW,
        COMPLETED,
        FAILED
    }

    /**
     * Maps an authorized refund operation to its public response.
     *
     * @param operation persisted refund operation
     * @return customer-visible refund details
     */
    public static CustomerRefundResponse from(
            RefundOperation operation) {

        Objects.requireNonNull(operation, "Refund operation is required");

        return new CustomerRefundResponse(
                operation.getId(),
                operation.getPaymentId(),
                operation.getBookingId(),
                operation.getAmount(),
                operation.getCurrency(),
                mapStatus(operation.getStatus()),
                operation.getCreatedAt(),
                operation.getUpdatedAt(),
                operation.getCompletedAt());
    }

    /**
     * Translates internal processing states without changing
     * the persisted refund lifecycle.
     */
    private static CustomerRefundStatus mapStatus(
            RefundStatus status) {

        Objects.requireNonNull(status, "Refund status is required");

        return switch (status) {
            case PENDING, PROCESSING ->
                    CustomerRefundStatus.PROCESSING;

            case SUCCEEDED ->
                    CustomerRefundStatus.COMPLETED;

            case FAILED ->
                    CustomerRefundStatus.FAILED;

            case REQUIRES_RECONCILIATION ->
                    CustomerRefundStatus.UNDER_REVIEW;
        };
    }
}
