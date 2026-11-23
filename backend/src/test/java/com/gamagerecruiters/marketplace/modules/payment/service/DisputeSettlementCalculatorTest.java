
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.service.BookingFinancialSnapshotService.FinancialSnapshot;

/**
 * Verifies booking-specific dispute settlement calculations.
 *
 * No database or gateway operations are performed by this calculator.
 */
class DisputeSettlementCalculatorTest {

    private final DisputeSettlementCalculator calculator = new DisputeSettlementCalculator();

    @Test
    void fullRefundUsesCapturedAmountAndProviderEarningsPenalty() {

        var result = calculator.calculate(
                snapshot(),
                ResolutionAction.REFUND_FULL,
                100,
                30);

        assertEquals(new BigDecimal("1000.00"),
                result.refundAmount());

        // 30% of provider earnings (850), not captured amount (1000).
        assertEquals(new BigDecimal("255.00"),
                result.providerPenaltyAmount());

        assertEquals(new BigDecimal("850.00"),
                result.originalProviderEarnings());
    }

    @Test
    void partialRefundCalculatesBothAmounts() {

        var result = calculator.calculate(
                snapshot(),
                ResolutionAction.REFUND_PARTIAL,
                40,
                30);

        assertEquals(new BigDecimal("400.00"),
                result.refundAmount());

        assertEquals(new BigDecimal("255.00"),
                result.providerPenaltyAmount());
    }

    @Test
    void releaseToProviderDoesNotApplyRefundOrPenalty() {

        var result = calculator.calculate(
                snapshot(),
                ResolutionAction.RELEASE_TO_PROVIDER,
                0,
                0);

        assertEquals(new BigDecimal("0.00"),
                result.refundAmount());

        assertEquals(new BigDecimal("0.00"),
                result.providerPenaltyAmount());
    }

    @Test
    void rejectsPenaltyOnReleaseDecision() {

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(
                        snapshot(),
                        ResolutionAction.RELEASE_TO_PROVIDER,
                        0,
                        30));
    }

    @Test
    void rejectsInvalidFullRefundPercentage() {

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(
                        snapshot(),
                        ResolutionAction.REFUND_FULL,
                        80,
                        30));
    }

    @Test
    void rejectsInvalidPartialRefundPercentage() {

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(
                        snapshot(),
                        ResolutionAction.REFUND_PARTIAL,
                        100,
                        30));
    }

    @Test
    void rejectsPercentageAboveOneHundred() {

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(
                        snapshot(),
                        ResolutionAction.REFUND_PARTIAL,
                        40,
                        110));
    }

    private FinancialSnapshot snapshot() {

        return new FinancialSnapshot(
                "payment-1",
                "booking-1",
                "customer-1",
                "provider-1",
                "LKR",
                PaymentStatus.CAPTURED,
                new BigDecimal("1000.00"),
                new BigDecimal("150.00"),
                new BigDecimal("850.00"),
                BigDecimal.ZERO);
    }
}
