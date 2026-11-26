package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies provider earnings after refunds occurring before recognition.
 *
 * Original payment and commission snapshots must remain the basis
 * of cumulative refund calculations.
 */
class RefundAdjustedEarningsCalculatorTest {

    private final RefundCommissionCalculator calculator =
            new RefundCommissionCalculator();

    @Test
    void noRefundPreservesOriginalProviderEarnings() {
        assertRemaining("1000.00", "150.00", "0.00", "850.00");
    }

    @Test
    void partialRefundReducesRecognizableProviderEarnings() {
        assertRemaining("1000.00", "150.00", "200.00", "680.00");
    }

    @Test
    void cumulativeRefundsPreserveOriginalCommissionBasis() {
        assertRemaining("1000.00", "150.00", "300.00", "595.00");
    }

    @Test
    void fullRefundLeavesNoRecognizableProviderEarnings() {
        assertRemaining("1000.00", "150.00", "1000.00", "0.00");
    }

    @Test
    void zeroCommissionPreservesRemainingCustomerPayment() {
        assertRemaining("1000.00", "0.00", "200.00", "800.00");
    }

    @Test
    void fullCommissionLeavesNoProviderEarnings() {
        assertRemaining("1000.00", "1000.00", "200.00", "0.00");
    }

    @Test
    void cumulativeRoundingUsesOriginalCommissionSnapshot() {
        assertRemaining("10.00", "1.33", "3.33", "5.78");
    }

    @Test
    void rejectsRefundExceedingOriginalPayment() {
        assertThrows(IllegalArgumentException.class, () ->
                calculator.calculateRemainingProviderEarnings(
                        money("1000.00"),
                        money("150.00"),
                        money("1000.01")));
    }

    @Test
    void rejectsNegativeRefundAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                calculator.calculateRemainingProviderEarnings(
                        money("1000.00"),
                        money("150.00"),
                        money("-1.00")));
    }

    @Test
    void rejectsCommissionExceedingOriginalPayment() {
        assertThrows(IllegalArgumentException.class, () ->
                calculator.calculateRemainingProviderEarnings(
                        money("1000.00"),
                        money("1000.01"),
                        money("200.00")));
    }

    private void assertRemaining(
            String payment,
            String commission,
            String refunded,
            String expected) {

        BigDecimal actual =
                calculator.calculateRemainingProviderEarnings(
                        money(payment),
                        money(commission),
                        money(refunded));

        assertEquals(0, money(expected).compareTo(actual));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}