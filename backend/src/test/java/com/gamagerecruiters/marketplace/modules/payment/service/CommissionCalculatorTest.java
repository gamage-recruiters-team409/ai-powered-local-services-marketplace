
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the platform commission calculation rules.
 *
 * These tests verify default and custom commission rates,
 * monetary rounding, provider earnings, and input validation.
 *
 * No database or payment gateway connection is required.
 */
class CommissionCalculatorTest {

    private CommissionCalculator calculator;

    /**
     * Creates a fresh calculator before each test.
     */
    @BeforeEach
    void setUp() {
        calculator = new CommissionCalculator();
    }

    /**
     * Verifies the default platform commission rate of 10%.
     */
    @Test
    void shouldCalculateDefaultCommission() {
        BigDecimal servicePrice = new BigDecimal("10000.00");

        BigDecimal commission = calculator.calculateDefaultCommission(servicePrice);

        assertEquals(new BigDecimal("1000.00"), commission);
    }

    /**
     * Verifies that a category-specific commission rate can be used.
     */
    @Test
    void shouldCalculateCustomCommission() {
        BigDecimal servicePrice = new BigDecimal("8000.00");
        BigDecimal customRate = new BigDecimal("0.15");

        BigDecimal commission = calculator.calculateCommission(servicePrice, customRate);

        assertEquals(new BigDecimal("1200.00"), commission);
    }

    /**
     * Verifies monetary rounding to two decimal places.
     */
    @Test
    void shouldRoundCommissionToTwoDecimalPlaces() {
        BigDecimal servicePrice = new BigDecimal("99.99");
        BigDecimal rate = new BigDecimal("0.10");

        BigDecimal commission = calculator.calculateCommission(servicePrice, rate);

        assertEquals(new BigDecimal("10.00"), commission);
    }

    /**
     * Verifies provider earnings after deducting commission.
     */
    @Test
    void shouldCalculateProviderEarnings() {
        BigDecimal servicePrice = new BigDecimal("10000.00");
        BigDecimal rate = new BigDecimal("0.10");

        BigDecimal earnings = calculator.calculateProviderEarnings(servicePrice, rate);

        assertEquals(new BigDecimal("9000.00"), earnings);
    }

    /**
     * Verifies that a zero service price produces zero commission.
     */
    @Test
    void shouldAllowZeroServicePrice() {
        BigDecimal commission = calculator.calculateDefaultCommission(BigDecimal.ZERO);

        assertEquals(new BigDecimal("0.00"), commission);
    }

    /**
     * Verifies that negative service prices are rejected.
     */
    @Test
    void shouldRejectNegativeServicePrice() {
        BigDecimal negativePrice = new BigDecimal("-100.00");

        assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculateDefaultCommission(negativePrice));
    }

    /**
     * Verifies that commission rates above 100% are rejected.
     */
    @Test
    void shouldRejectCommissionRateAboveOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculateCommission(
                        new BigDecimal("1000.00"),
                        new BigDecimal("1.50")));
    }

    /**
     * Verifies that negative commission rates are rejected.
     */
    @Test
    void shouldRejectNegativeCommissionRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculateCommission(
                        new BigDecimal("1000.00"),
                        new BigDecimal("-0.10")));
    }

    /**
     * Verifies that a missing service price is rejected.
     */
    @Test
    void shouldRejectNullServicePrice() {
        assertThrows(
                NullPointerException.class,
                () -> calculator.calculateDefaultCommission(null));
    }

    /**
     * Verifies that a missing commission rate is rejected.
     */
    @Test
    void shouldRejectNullCommissionRate() {
        assertThrows(
                NullPointerException.class,
                () -> calculator.calculateCommission(
                        new BigDecimal("1000.00"),
                        null));
    }
}
