package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Calculates proportional commission and provider earnings reversals
 * for full and partial refunds.
 *
 * <p>
 * Calculations use the original persisted commission amount rather than
 * recalculating commission using a potentially changed commission rate.
 * </p>
 *
 * <p>
 * Cumulative rounding prevents rounding discrepancies across multiple
 * partial refunds and ensures that a complete refund reverses the
 * entire original commission and provider earnings.
 * </p>
 *
 * <p>
 * This calculator assumes that the original payment amount represents
 * the service price without separately allocated taxes or platform fees.
 * </p>
 *
 * <p>
 * This component performs calculations only. It does not authorize
 * refunds, update payment records, modify earnings balances, or
 * communicate with a payment gateway.
 * </p>
 */
@Component
public class RefundCommissionCalculator {

    private static final int MONEY_SCALE = 2;

    /**
     * Calculates the financial adjustments for an incremental refund.
     *
     * @param originalPaymentAmount    original captured service payment amount
     * @param originalCommissionAmount original persisted platform commission
     * @param previouslyRefundedAmount cumulative successful refunds
     * @param requestedRefundAmount    additional amount to refund
     * @return immutable refund allocation amounts
     * @throws NullPointerException     if a required amount is null
     * @throws IllegalArgumentException if monetary inputs are invalid
     *                                  or the requested refund exceeds the
     *                                  remaining payment
     */
    public RefundAmounts calculate(
            BigDecimal originalPaymentAmount,
            BigDecimal originalCommissionAmount,
            BigDecimal previouslyRefundedAmount,
            BigDecimal requestedRefundAmount) {

        BigDecimal payment = requireMoney(
                originalPaymentAmount, "Original payment amount");

        BigDecimal commission = requireMoney(
                originalCommissionAmount, "Original commission amount");

        BigDecimal previousRefund = requireMoney(
                previouslyRefundedAmount, "Previously refunded amount");

        BigDecimal currentRefund = requireMoney(
                requestedRefundAmount, "Requested refund amount");

        if (payment.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Original payment amount must be positive");
        }

        if (commission.compareTo(payment) > 0) {
            throw new IllegalArgumentException(
                    "Original commission cannot exceed original payment");
        }

        if (previousRefund.compareTo(payment) > 0) {
            throw new IllegalArgumentException(
                    "Previously refunded amount exceeds original payment");
        }

        if (currentRefund.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Requested refund amount must be positive");
        }

        BigDecimal cumulativeRefund = previousRefund.add(currentRefund);

        // Prevent refunds from exceeding the original captured amount.
        if (cumulativeRefund.compareTo(payment) > 0) {
            throw new IllegalArgumentException(
                    "Requested refund exceeds remaining refundable amount");
        }

        // Calculate cumulative commission reversals before and after
        // the requested refund to avoid repeated rounding discrepancies.
        BigDecimal previousCommissionReversal = calculateCumulativeCommissionReversal(
                payment,
                commission,
                previousRefund);

        BigDecimal cumulativeCommissionReversal = calculateCumulativeCommissionReversal(
                payment,
                commission,
                cumulativeRefund);

        // Only reverse the additional commission for this refund.
        BigDecimal commissionReversal = cumulativeCommissionReversal.subtract(
                previousCommissionReversal);

        // The refund is funded by commission and provider earnings.
        BigDecimal providerEarningsReversal = currentRefund.subtract(commissionReversal);

        BigDecimal remainingPayment = payment.subtract(cumulativeRefund);

        BigDecimal remainingCommission = commission.subtract(cumulativeCommissionReversal);

        BigDecimal remainingProviderEarnings = remainingPayment.subtract(remainingCommission);

        return new RefundAmounts(
                currentRefund,
                commissionReversal,
                providerEarningsReversal,
                cumulativeRefund,
                cumulativeCommissionReversal,
                remainingPayment,
                remainingCommission,
                remainingProviderEarnings);
    }

    /**
     * Calculates the cumulative commission reversal for the total
     * amount refunded against the original payment.
     */
    private BigDecimal calculateCumulativeCommissionReversal(
            BigDecimal originalPayment,
            BigDecimal originalCommission,
            BigDecimal cumulativeRefund) {

        // A complete refund must reverse the entire stored commission.
        if (cumulativeRefund.compareTo(originalPayment) == 0) {
            return originalCommission;
        }

        return originalCommission
                .multiply(cumulativeRefund)
                .divide(
                        originalPayment,
                        MONEY_SCALE,
                        RoundingMode.HALF_UP);
    }

    /**
     * Validates and normalizes monetary amounts without silently
     * rounding invalid fractional values.
     */
    private BigDecimal requireMoney(
            BigDecimal amount,
            String fieldName) {

        Objects.requireNonNull(
                amount, fieldName + " must not be null");

        if (amount.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must not be negative");
        }

        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    fieldName + " must not contain more than two decimal places");
        }

        return amount.setScale(
                MONEY_SCALE,
                RoundingMode.UNNECESSARY);
    }

    /**
     * Immutable financial allocation for an incremental refund.
     *
     * All monetary values are represented with two decimal places.
     */
    public record RefundAmounts(
            BigDecimal refundAmount,
            BigDecimal commissionReversalAmount,
            BigDecimal providerEarningsReversalAmount,
            BigDecimal cumulativeRefundedAmount,
            BigDecimal cumulativeCommissionReversalAmount,
            BigDecimal remainingPaymentAmount,
            BigDecimal remainingCommissionAmount,
            BigDecimal remainingProviderEarningsAmount) {
    }
}