
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Calculates the platform commission for a service payment.
 *
 * <p>
 * The default commission rate is 10%. A different rate can be
 * supplied when a service category has its own commission policy.
 * </p>
 *
 * <p>
 * This class performs calculations only. It does not update
 * payment records or communicate with a payment gateway.
 * </p>
 */
public class CommissionCalculator {

    // Default platform commission: 10% of the service price.
    private static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.10");

    // Store calculated monetary amounts with two decimal places.
    private static final int MONEY_SCALE = 2;

    /**
     * Calculates commission using the default 10% rate.
     *
     * @param servicePrice the service price before commission
     * @return the calculated platform commission
     */
    public BigDecimal calculateDefaultCommission(BigDecimal servicePrice) {
        return calculateCommission(servicePrice, DEFAULT_COMMISSION_RATE);
    }

    /**
     * Calculates commission using a supplied rate.
     *
     * <p>
     * Rates are represented as decimal fractions:
     * 0.10 = 10%, 0.15 = 15%, and 0.20 = 20%.
     * </p>
     *
     * @param servicePrice   the service price before commission
     * @param commissionRate the applicable commission rate
     * @return commission rounded to two decimal places
     * @throws IllegalArgumentException if an input is invalid
     */
    public BigDecimal calculateCommission(
            BigDecimal servicePrice,
            BigDecimal commissionRate) {

        validateServicePrice(servicePrice);
        validateCommissionRate(commissionRate);

        // Use BigDecimal multiplication to avoid floating-point errors.
        return servicePrice
                .multiply(commissionRate)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the provider's earnings after platform commission.
     *
     * <p>
     * This calculation does not include penalties, taxes,
     * refunds, or additional charges.
     * </p>
     *
     * @param servicePrice   the service price
     * @param commissionRate the applicable commission rate
     * @return the provider's net amount before other adjustments
     */
    public BigDecimal calculateProviderEarnings(
            BigDecimal servicePrice,
            BigDecimal commissionRate) {

        BigDecimal commission = calculateCommission(servicePrice, commissionRate);

        return servicePrice
                .subtract(commission)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Ensures the service price is present and non-negative.
     */
    private void validateServicePrice(BigDecimal servicePrice) {
        Objects.requireNonNull(servicePrice, "Service price must not be null");

        if (servicePrice.signum() < 0) {
            throw new IllegalArgumentException(
                    "Service price must not be negative");
        }
    }

    /**
     * Ensures the commission rate is between 0% and 100%.
     */
    private void validateCommissionRate(BigDecimal commissionRate) {
        Objects.requireNonNull(
                commissionRate, "Commission rate must not be null");

        if (commissionRate.compareTo(BigDecimal.ZERO) < 0
                || commissionRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "Commission rate must be between 0 and 1");
        }
    }
}
