package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Immutable financial allocation reserved for a refund operation.
 *
 * <p>
 * Preserves the original payment state and the financial amounts
 * required for refund execution, rollback and reconciliation.
 * </p>
 *
 * <p>
 * This value object does not independently reserve funds.
 * Reservation and persistence must be performed atomically
 * by the refund transaction service.
 * </p>
 */
public record RefundReservation(

        PaymentStatus originalPaymentStatus,

        @Field(targetType = FieldType.DECIMAL128) BigDecimal previouslyRefundedAmount,

        @Field(targetType = FieldType.DECIMAL128) BigDecimal commissionReversalAmount,

        @Field(targetType = FieldType.DECIMAL128) BigDecimal providerEarningsReversalAmount,

        @Field(targetType = FieldType.DECIMAL128) BigDecimal pendingAllocation,

        @Field(targetType = FieldType.DECIMAL128) BigDecimal availableAllocation,

        @Field(targetType = FieldType.DECIMAL128) BigDecimal heldAllocation,

        String providerId) {

    /**
     * Validates and normalizes the reserved financial allocation.
     */
    public RefundReservation {

        Objects.requireNonNull(
                originalPaymentStatus,
                "Original payment status is required");

        previouslyRefundedAmount = requireMoney(
                previouslyRefundedAmount,
                "Previously refunded amount");

        commissionReversalAmount = requireMoney(
                commissionReversalAmount,
                "Commission reversal amount");

        providerEarningsReversalAmount = requireMoney(
                providerEarningsReversalAmount,
                "Provider earnings reversal amount");

        pendingAllocation = requireMoney(
                pendingAllocation,
                "Pending allocation");

        availableAllocation = requireMoney(
                availableAllocation,
                "Available allocation");

        heldAllocation = requireMoney(
                heldAllocation,
                "Held allocation");

        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider ID is required");
        }

        providerId = providerId.trim();

        BigDecimal totalAllocation = pendingAllocation
                .add(availableAllocation)
                .add(heldAllocation);

        // Existing ledger allocations must not exceed
        // the calculated provider earnings reversal.
        if (totalAllocation.compareTo(
                providerEarningsReversalAmount) > 0) {

            throw new IllegalArgumentException(
                    "Reserved earnings exceed provider reversal amount");
        }
    }

    /**
     * Returns the total amount allocated from existing earnings.
     *
     * @return total reserved ledger amount
     */
    public BigDecimal totalAllocatedEarnings() {

        return pendingAllocation
                .add(availableAllocation)
                .add(heldAllocation);
    }

    /**
     * Returns the reversal amount not allocated from existing
     * ledger balances.
     *
     * <p>
     * The transaction service must verify whether this amount
     * represents earnings that have not yet been recognized.
     * </p>
     *
     * @return unallocated provider reversal amount
     */
    public BigDecimal unallocatedProviderReversalAmount() {

        return providerEarningsReversalAmount
                .subtract(totalAllocatedEarnings());
    }

    /**
     * Validates a non-negative monetary amount without rounding.
     */
    private static BigDecimal requireMoney(
            BigDecimal amount,
            String fieldName) {

        Objects.requireNonNull(
                amount, fieldName + " is required");

        if (amount.signum() < 0 || amount.scale() > 2) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must be non-negative "
                            + "with at most two decimal places");
        }

        return amount.setScale(
                2, RoundingMode.UNNECESSARY);
    }
}