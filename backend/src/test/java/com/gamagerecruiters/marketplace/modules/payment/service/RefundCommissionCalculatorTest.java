package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.service.RefundCommissionCalculator.RefundAmounts;

/**
 * Verifies proportional refund commission allocation, cumulative
 * rounding, monetary validation, and financial consistency.
 *
 * These tests require no database, Spring application context,
 * or external payment gateway.
 */
class RefundCommissionCalculatorTest {

    private final RefundCommissionCalculator calculator = new RefundCommissionCalculator();

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    // ---------------------------------------------------------
    // Full and partial refund calculations
    // ---------------------------------------------------------

    @Test
    @DisplayName("Full refund reverses the entire commission and provider earnings")
    void fullRefundReversesEntireFinancialAllocation() {

        RefundAmounts result = calculate(
                "10000.00", "1000.00", "0.00", "10000.00");

        assertAmounts(
                result,
                "10000.00", "1000.00", "9000.00",
                "10000.00", "1000.00",
                "0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("50 percent refund reverses commission proportionally")
    void halfRefundReversesProportionalAmounts() {

        RefundAmounts result = calculate(
                "10000.00", "1000.00", "0.00", "5000.00");

        assertAmounts(
                result,
                "5000.00", "500.00", "4500.00",
                "5000.00", "500.00",
                "5000.00", "500.00", "4500.00");
    }

    @Test
    @DisplayName("25 percent refund preserves the remaining financial allocation")
    void quarterRefundCalculatesCorrectAmounts() {

        RefundAmounts result = calculate(
                "10000.00", "1000.00", "0.00", "2500.00");

        assertAmounts(
                result,
                "2500.00", "250.00", "2250.00",
                "2500.00", "250.00",
                "7500.00", "750.00", "6750.00");
    }

    @Test
    @DisplayName("Refund uses the original stored commission instead of the default rate")
    void usesOriginalStoredCommission() {

        RefundAmounts result = calculate(
                "1000.00", "175.00", "0.00", "400.00");

        assertAmounts(
                result,
                "400.00", "70.00", "330.00",
                "400.00", "70.00",
                "600.00", "105.00", "495.00");
    }

    @Test
    @DisplayName("Additional refund accounts for previous successful refunds")
    void additionalRefundAccountsForPreviousRefunds() {

        RefundAmounts result = calculate(
                "10000.00", "1000.00", "2000.00", "3000.00");

        assertAmounts(
                result,
                "3000.00", "300.00", "2700.00",
                "5000.00", "500.00",
                "5000.00", "500.00", "4500.00");
    }

    @Test
    @DisplayName("Final refund after a partial refund leaves no remaining balance")
    void finalRefundClearsRemainingAmounts() {

        RefundAmounts result = calculate(
                "10000.00", "1000.00", "3500.00", "6500.00");

        assertAmounts(
                result,
                "6500.00", "650.00", "5850.00",
                "10000.00", "1000.00",
                "0.00", "0.00", "0.00");
    }

    // ---------------------------------------------------------
    // Cumulative rounding and monetary precision
    // ---------------------------------------------------------

    @Test
    @DisplayName("Multiple partial refunds reverse the exact original commission")
    void multiplePartialRefundsPreserveOriginalTotals() {

        RefundAmounts first = calculate(
                "10000.00", "1000.00", "0.00", "3333.33");

        RefundAmounts second = calculate(
                "10000.00", "1000.00", "3333.33", "3333.33");

        RefundAmounts third = calculate(
                "10000.00", "1000.00", "6666.66", "3333.34");

        assertEquals(money("333.33"),
                first.commissionReversalAmount());

        assertEquals(money("333.34"),
                second.commissionReversalAmount());

        assertEquals(money("333.33"),
                third.commissionReversalAmount());

        BigDecimal totalCommissionReversal = first.commissionReversalAmount()
                .add(second.commissionReversalAmount())
                .add(third.commissionReversalAmount());

        BigDecimal totalProviderReversal = first.providerEarningsReversalAmount()
                .add(second.providerEarningsReversalAmount())
                .add(third.providerEarningsReversalAmount());

        assertEquals(money("1000.00"), totalCommissionReversal);
        assertEquals(money("9000.00"), totalProviderReversal);

        assertEquals(ZERO, third.remainingPaymentAmount());
        assertEquals(ZERO, third.remainingCommissionAmount());
        assertEquals(ZERO, third.remainingProviderEarningsAmount());
    }

    @Test
    @DisplayName("Cumulative rounding prevents repeated half-cent over-reversal")
    void cumulativeRoundingPreventsOverReversal() {

        RefundAmounts first = calculate(
                "0.02", "0.01", "0.00", "0.01");

        RefundAmounts second = calculate(
                "0.02", "0.01", "0.01", "0.01");

        assertEquals(money("0.01"),
                first.commissionReversalAmount());

        assertEquals(ZERO,
                second.commissionReversalAmount());

        assertEquals(ZERO,
                first.providerEarningsReversalAmount());

        assertEquals(money("0.01"),
                second.providerEarningsReversalAmount());

        assertEquals(ZERO, second.remainingPaymentAmount());
        assertEquals(ZERO, second.remainingCommissionAmount());
        assertEquals(ZERO, second.remainingProviderEarningsAmount());
    }

    @Test
    @DisplayName("Commission reversal uses HALF_UP monetary rounding")
    void appliesHalfUpRounding() {

        RefundAmounts result = calculate(
                "100.00", "10.00", "0.00", "0.05");

        assertEquals(money("0.01"),
                result.commissionReversalAmount());

        assertEquals(money("0.04"),
                result.providerEarningsReversalAmount());
    }

    @Test
    @DisplayName("Refund allocation is consistent regardless of refund partition")
    void refundPartitionDoesNotChangeCumulativeAllocation() {

        RefundAmounts single = calculate(
                "100.00", "17.33", "0.00", "66.67");

        RefundAmounts first = calculate(
                "100.00", "17.33", "0.00", "33.33");

        RefundAmounts second = calculate(
                "100.00", "17.33", "33.33", "33.34");

        assertEquals(
                single.commissionReversalAmount(),
                first.commissionReversalAmount()
                        .add(second.commissionReversalAmount()));

        assertEquals(
                single.providerEarningsReversalAmount(),
                first.providerEarningsReversalAmount()
                        .add(second.providerEarningsReversalAmount()));

        assertEquals(
                single.remainingCommissionAmount(),
                second.remainingCommissionAmount());

        assertEquals(
                single.remainingProviderEarningsAmount(),
                second.remainingProviderEarningsAmount());
    }

    @Test
    @DisplayName("Minimum positive monetary refund is supported")
    void supportsMinimumPositiveRefund() {

        RefundAmounts result = calculate(
                "100.00", "10.00", "0.00", "0.01");

        assertAmounts(
                result,
                "0.01", "0.00", "0.01",
                "0.01", "0.00",
                "99.99", "10.00", "89.99");
    }

    @Test
    @DisplayName("Whole number monetary inputs are normalized to two decimal places")
    void normalizesWholeNumberInputs() {

        RefundAmounts result = calculate(
                "1000", "100", "0", "500");

        assertAmounts(
                result,
                "500.00", "50.00", "450.00",
                "500.00", "50.00",
                "500.00", "50.00", "450.00");
    }

    @Test
    @DisplayName("Trailing zeros do not cause monetary precision rejection")
    void acceptsTrailingZeroPrecision() {

        RefundAmounts result = calculate(
                "1000.0000", "100.000", "0.000", "500.0000");

        assertAmounts(
                result,
                "500.00", "50.00", "450.00",
                "500.00", "50.00",
                "500.00", "50.00", "450.00");
    }

    // ---------------------------------------------------------
    // Commission boundary conditions
    // ---------------------------------------------------------

    @Test
    @DisplayName("Zero commission allocates the entire refund to provider earnings")
    void zeroCommissionAllocatesRefundToProvider() {

        RefundAmounts result = calculate(
                "1000.00", "0.00", "0.00", "400.00");

        assertAmounts(
                result,
                "400.00", "0.00", "400.00",
                "400.00", "0.00",
                "600.00", "0.00", "600.00");
    }

    @Test
    @DisplayName("Full refund with zero commission clears provider earnings")
    void fullRefundWithZeroCommission() {

        RefundAmounts result = calculate(
                "1000.00", "0.00", "0.00", "1000.00");

        assertAmounts(
                result,
                "1000.00", "0.00", "1000.00",
                "1000.00", "0.00",
                "0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("100 percent commission allocates the entire refund to commission")
    void fullCommissionAllocatesRefundToPlatform() {

        RefundAmounts result = calculate(
                "1000.00", "1000.00", "0.00", "400.00");

        assertAmounts(
                result,
                "400.00", "400.00", "0.00",
                "400.00", "400.00",
                "600.00", "600.00", "0.00");
    }

    @Test
    @DisplayName("Full refund with 100 percent commission clears platform commission")
    void fullRefundWithFullCommission() {

        RefundAmounts result = calculate(
                "1000.00", "1000.00", "0.00", "1000.00");

        assertAmounts(
                result,
                "1000.00", "1000.00", "0.00",
                "1000.00", "1000.00",
                "0.00", "0.00", "0.00");
    }

    // ---------------------------------------------------------
    // Refund limit validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Rejects a refund exceeding the original payment")
    void rejectsRefundExceedingOriginalPayment() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "0.00", "1000.01"));
    }

    @Test
    @DisplayName("Rejects a refund exceeding the remaining refundable amount")
    void rejectsRefundExceedingRemainingAmount() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "600.00", "400.01"));
    }

    @Test
    @DisplayName("Rejects additional refunds after a complete refund")
    void rejectsRefundAfterCompleteRefund() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "1000.00", "0.01"));
    }

    @Test
    @DisplayName("Rejects an invalid previously refunded amount")
    void rejectsPreviousRefundExceedingOriginalPayment() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "1000.01", "0.01"));
    }

    @Test
    @DisplayName("Rejects commission exceeding the original payment")
    void rejectsCommissionExceedingOriginalPayment() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "1000.01", "0.00", "100.00"));
    }

    // ---------------------------------------------------------
    // Zero and negative monetary validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Rejects a zero original payment")
    void rejectsZeroOriginalPayment() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "0.00", "0.00", "0.00", "0.01"));
    }

    @Test
    @DisplayName("Rejects a zero requested refund")
    void rejectsZeroRequestedRefund() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "0.00", "0.00"));
    }

    @Test
    @DisplayName("Rejects a negative original payment")
    void rejectsNegativeOriginalPayment() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "-1000.00", "100.00", "0.00", "100.00"));
    }

    @Test
    @DisplayName("Rejects a negative original commission")
    void rejectsNegativeOriginalCommission() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "-100.00", "0.00", "100.00"));
    }

    @Test
    @DisplayName("Rejects a negative previously refunded amount")
    void rejectsNegativePreviousRefund() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "-0.01", "100.00"));
    }

    @Test
    @DisplayName("Rejects a negative requested refund")
    void rejectsNegativeRequestedRefund() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "0.00", "-100.00"));
    }

    // ---------------------------------------------------------
    // Null input validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Rejects a null original payment")
    void rejectsNullOriginalPayment() {

        assertThrows(
                NullPointerException.class,
                () -> calculator.calculate(
                        null, money("100.00"), ZERO, money("50.00")));
    }

    @Test
    @DisplayName("Rejects a null original commission")
    void rejectsNullOriginalCommission() {

        assertThrows(
                NullPointerException.class,
                () -> calculator.calculate(
                        money("1000.00"), null, ZERO, money("50.00")));
    }

    @Test
    @DisplayName("Rejects a null previously refunded amount")
    void rejectsNullPreviousRefund() {

        assertThrows(
                NullPointerException.class,
                () -> calculator.calculate(
                        money("1000.00"), money("100.00"),
                        null, money("50.00")));
    }

    @Test
    @DisplayName("Rejects a null requested refund")
    void rejectsNullRequestedRefund() {

        assertThrows(
                NullPointerException.class,
                () -> calculator.calculate(
                        money("1000.00"), money("100.00"),
                        ZERO, null));
    }

    // ---------------------------------------------------------
    // Invalid monetary precision
    // ---------------------------------------------------------

    @Test
    @DisplayName("Rejects fractional cents in the original payment")
    void rejectsInvalidOriginalPaymentPrecision() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.001", "100.00", "0.00", "100.00"));
    }

    @Test
    @DisplayName("Rejects fractional cents in the original commission")
    void rejectsInvalidCommissionPrecision() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.001", "0.00", "100.00"));
    }

    @Test
    @DisplayName("Rejects fractional cents in previously refunded amounts")
    void rejectsInvalidPreviousRefundPrecision() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "0.001", "100.00"));
    }

    @Test
    @DisplayName("Rejects fractional cents in the requested refund")
    void rejectsInvalidRequestedRefundPrecision() {

        assertThrows(
                IllegalArgumentException.class,
                () -> calculate(
                        "1000.00", "100.00", "0.00", "100.001"));
    }

    // ---------------------------------------------------------
    // Financial consistency and boundary verification
    // ---------------------------------------------------------

    @Test
    @DisplayName("Every refund equals commission reversal plus provider reversal")
    void preservesRefundAllocationIdentity() {

        RefundAmounts result = calculate(
                "999.99", "123.45", "111.11", "222.22");

        assertEquals(
                result.refundAmount(),
                result.commissionReversalAmount()
                        .add(result.providerEarningsReversalAmount()));
    }

    @Test
    @DisplayName("Remaining payment equals remaining commission plus provider earnings")
    void preservesRemainingAllocationIdentity() {

        RefundAmounts result = calculate(
                "999.99", "123.45", "111.11", "222.22");

        assertEquals(
                result.remainingPaymentAmount(),
                result.remainingCommissionAmount()
                        .add(result.remainingProviderEarningsAmount()));
    }

    @Test
    @DisplayName("Original commission equals cumulative reversal plus remaining commission")
    void preservesOriginalCommissionIdentity() {

        RefundAmounts result = calculate(
                "999.99", "123.45", "111.11", "222.22");

        assertEquals(
                money("123.45"),
                result.cumulativeCommissionReversalAmount()
                        .add(result.remainingCommissionAmount()));
    }

    @Test
    @DisplayName("Original payment equals cumulative refunds plus remaining payment")
    void preservesOriginalPaymentIdentity() {

        RefundAmounts result = calculate(
                "999.99", "123.45", "111.11", "222.22");

        assertEquals(
                money("999.99"),
                result.cumulativeRefundedAmount()
                        .add(result.remainingPaymentAmount()));
    }

    @Test
    @DisplayName("All monetary result fields have exactly two decimal places")
    void returnsConsistentMonetaryScale() {

        RefundAmounts result = calculate(
                "1000", "123.45", "50", "100");

        BigDecimal[] amounts = {
                result.refundAmount(),
                result.commissionReversalAmount(),
                result.providerEarningsReversalAmount(),
                result.cumulativeRefundedAmount(),
                result.cumulativeCommissionReversalAmount(),
                result.remainingPaymentAmount(),
                result.remainingCommissionAmount(),
                result.remainingProviderEarningsAmount()
        };

        for (BigDecimal amount : amounts) {
            assertEquals(2, amount.scale());
        }
    }

    @Test
    @DisplayName("Supports large monetary values without floating-point loss")
    void supportsLargeMonetaryValues() {

        RefundAmounts result = calculate(
                "999999999999.99",
                "99999999999.99",
                "0.00",
                "999999999999.99");

        assertAmounts(
                result,
                "999999999999.99",
                "99999999999.99",
                "900000000000.00",
                "999999999999.99",
                "99999999999.99",
                "0.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("Repeated identical calculations produce identical results")
    void calculationIsDeterministic() {

        RefundAmounts first = calculate(
                "999.99", "123.45", "111.11", "222.22");

        RefundAmounts second = calculate(
                "999.99", "123.45", "111.11", "222.22");

        assertEquals(first, second);
    }

    @Test
    @DisplayName("Small-value refund combinations preserve all financial invariants")
    void smallValueCombinationsPreserveFinancialInvariants() {

        // Exhaustively verify valid payment, commission, previous
        // refund, and incremental refund combinations up to 20 cents.
        for (int paymentCents = 1; paymentCents <= 20; paymentCents++) {

            BigDecimal payment = cents(paymentCents);

            for (int commissionCents = 0; commissionCents <= paymentCents; commissionCents++) {

                BigDecimal commission = cents(commissionCents);

                for (int previousCents = 0; previousCents < paymentCents; previousCents++) {

                    BigDecimal previous = cents(previousCents);

                    for (int refundCents = 1; refundCents <= paymentCents - previousCents; refundCents++) {

                        BigDecimal refund = cents(refundCents);

                        RefundAmounts result = calculator.calculate(
                                payment,
                                commission,
                                previous,
                                refund);

                        assertFinancialInvariants(
                                result,
                                payment,
                                commission,
                                previous,
                                refund);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------

    private RefundAmounts calculate(
            String payment,
            String commission,
            String previousRefund,
            String requestedRefund) {

        return calculator.calculate(
                money(payment),
                money(commission),
                money(previousRefund),
                money(requestedRefund));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal cents(int value) {
        return BigDecimal.valueOf(value, 2);
    }

    /**
     * Verifies every field of a refund allocation result.
     */
    private static void assertAmounts(
            RefundAmounts result,
            String refund,
            String commissionReversal,
            String providerReversal,
            String cumulativeRefund,
            String cumulativeCommissionReversal,
            String remainingPayment,
            String remainingCommission,
            String remainingProviderEarnings) {

        assertEquals(
                money(refund),
                result.refundAmount());

        assertEquals(
                money(commissionReversal),
                result.commissionReversalAmount());

        assertEquals(
                money(providerReversal),
                result.providerEarningsReversalAmount());

        assertEquals(
                money(cumulativeRefund),
                result.cumulativeRefundedAmount());

        assertEquals(
                money(cumulativeCommissionReversal),
                result.cumulativeCommissionReversalAmount());

        assertEquals(
                money(remainingPayment),
                result.remainingPaymentAmount());

        assertEquals(
                money(remainingCommission),
                result.remainingCommissionAmount());

        assertEquals(
                money(remainingProviderEarnings),
                result.remainingProviderEarningsAmount());
    }

    /**
     * Verifies monetary conservation and non-negative balances
     * for every valid small-value refund combination.
     */
    private static void assertFinancialInvariants(
            RefundAmounts result,
            BigDecimal payment,
            BigDecimal commission,
            BigDecimal previous,
            BigDecimal refund) {

        assertEquals(refund, result.refundAmount());

        assertEquals(
                previous.add(refund),
                result.cumulativeRefundedAmount());

        assertEquals(
                refund,
                result.commissionReversalAmount()
                        .add(result.providerEarningsReversalAmount()));

        assertEquals(
                payment,
                result.cumulativeRefundedAmount()
                        .add(result.remainingPaymentAmount()));

        assertEquals(
                commission,
                result.cumulativeCommissionReversalAmount()
                        .add(result.remainingCommissionAmount()));

        assertEquals(
                result.remainingPaymentAmount(),
                result.remainingCommissionAmount()
                        .add(result.remainingProviderEarningsAmount()));

        assertTrue(result.commissionReversalAmount().signum() >= 0);
        assertTrue(result.providerEarningsReversalAmount().signum() >= 0);
        assertTrue(result.remainingPaymentAmount().signum() >= 0);
        assertTrue(result.remainingCommissionAmount().signum() >= 0);
        assertTrue(result.remainingProviderEarningsAmount().signum() >= 0);

        assertTrue(
                result.cumulativeCommissionReversalAmount()
                        .compareTo(commission) <= 0);

        assertTrue(
                result.cumulativeRefundedAmount()
                        .compareTo(payment) <= 0);

        BigDecimal cumulativeProviderReversal = result.cumulativeRefundedAmount()
                .subtract(
                        result.cumulativeCommissionReversalAmount());

        assertEquals(
                payment.subtract(commission),
                cumulativeProviderReversal.add(
                        result.remainingProviderEarningsAmount()));

        if (result.cumulativeRefundedAmount().compareTo(payment) == 0) {

            assertEquals(ZERO, result.remainingPaymentAmount());
            assertEquals(ZERO, result.remainingCommissionAmount());
            assertEquals(ZERO, result.remainingProviderEarningsAmount());

            assertEquals(
                    commission,
                    result.cumulativeCommissionReversalAmount());
        }
    }
}