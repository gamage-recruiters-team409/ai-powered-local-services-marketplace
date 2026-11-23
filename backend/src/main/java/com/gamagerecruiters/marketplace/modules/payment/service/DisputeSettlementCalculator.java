
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

/**
 * Calculates dispute settlement amounts without changing financial state.
 *
 * Commission refund allocation and actual ledger settlement are handled
 * separately after the business rules are confirmed.
 */
@Component
public class DisputeSettlementCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public SettlementAmounts calculate(
            BookingFinancialSnapshotService.FinancialSnapshot snapshot,
            ResolutionAction action,
            int refundPercent,
            int penaltyPercent) {

        Objects.requireNonNull(snapshot, "Financial snapshot is required");
        Objects.requireNonNull(action, "Resolution action is required");

        validatePercent(refundPercent, "Refund percentage");
        validatePercent(penaltyPercent, "Penalty percentage");

        BigDecimal capturedAmount = snapshot.capturedAmount();
        BigDecimal providerEarnings = snapshot.providerEarnings();

        if (capturedAmount == null || capturedAmount.signum() <= 0
                || providerEarnings == null
                || providerEarnings.signum() < 0) {
            throw new IllegalStateException(
                    "Invalid financial snapshot amounts");
        }

        // Only refund decisions may charge a provider penalty.
        boolean refundDecision = switch (action) {
            case REFUND_FULL, REFUND_PARTIAL -> true;
            case RELEASE_TO_PROVIDER -> false;
        };

        if (!refundDecision && (refundPercent != 0 || penaltyPercent != 0)) {
            throw new IllegalArgumentException(
                    "Release decision cannot contain refund or penalty");
        }

        if (action == ResolutionAction.REFUND_FULL
                && refundPercent != 100) {
            throw new IllegalArgumentException(
                    "Full refund must refund 100 percent");
        }

        if (action == ResolutionAction.REFUND_PARTIAL
                && (refundPercent <= 0 || refundPercent >= 100)) {
            throw new IllegalArgumentException(
                    "Partial refund percentage must be between 1 and 99");
        }

        BigDecimal refundAmount = refundDecision
                ? percentage(capturedAmount, refundPercent)
                : BigDecimal.ZERO.setScale(2);

        BigDecimal penaltyAmount = refundDecision
                ? percentage(providerEarnings, penaltyPercent)
                : BigDecimal.ZERO.setScale(2);

        return new SettlementAmounts(
                refundAmount,
                penaltyAmount,
                providerEarnings.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static BigDecimal percentage(
            BigDecimal amount, int percent) {

        return amount.multiply(BigDecimal.valueOf(percent))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static void validatePercent(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and 100");
        }
    }

    public record SettlementAmounts(
            BigDecimal refundAmount,
            BigDecimal providerPenaltyAmount,
            BigDecimal originalProviderEarnings) {
    }
}
