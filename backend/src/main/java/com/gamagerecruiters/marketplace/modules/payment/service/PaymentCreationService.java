
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Creates validated payment records with a commission snapshot.
 *
 * <p>
 * This service prepares payment data only. It does not persist records,
 * authorize bookings, or communicate with a payment gateway.
 * </p>
 */
public class PaymentCreationService {

    private final CommissionCalculator commissionCalculator;

    /**
     * Creates the service with the commission calculator dependency.
     *
     * @param commissionCalculator calculator used for commission amounts
     */
    public PaymentCreationService(CommissionCalculator commissionCalculator) {
        this.commissionCalculator = Objects.requireNonNull(
                commissionCalculator, "Commission calculator must not be null");
    }

    /**
     * Prepares a new payment with its applicable commission snapshot.
     *
     * @param bookingId      identifier of the associated booking
     * @param amount         positive payment amount
     * @param currency       ISO 4217 currency code
     * @param commissionRate commission rate represented as a fraction
     * @return initialized payment record, not yet persisted
     * @throws IllegalArgumentException if payment details are invalid
     */
    public Payment createPayment(
            String bookingId,
            BigDecimal amount,
            String currency,
            BigDecimal commissionRate) {

        validateBookingId(bookingId);
        validateAmount(amount);
        String normalizedCurrency = validateCurrency(currency);

        // Calculate once and store the rate and amount for this payment.
        BigDecimal commissionAmount = commissionCalculator
                .calculateCommission(amount, commissionRate);

        Instant now = Instant.now();

        Payment payment = new Payment();
        payment.setBookingId(bookingId.trim());
        payment.setAmount(amount);
        payment.setCurrency(normalizedCurrency);
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setRefundedAmount(BigDecimal.ZERO.setScale(2));
        payment.setCommissionRate(commissionRate);
        payment.setCommissionAmount(commissionAmount);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);

        return payment;
    }

    /**
     * Rejects missing or blank booking identifiers.
     */
    private void validateBookingId(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException(
                    "Booking ID must not be blank");
        }
    }

    /**
     * Requires a positive monetary amount with at most two decimals.
     */
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero");
        }

        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Payment amount must not exceed two decimal places");
        }
    }

    /**
     * Validates and normalizes an ISO 4217 currency code.
     */
    private String validateCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "Currency must not be blank");
        }

        String normalized = currency.trim().toUpperCase(
                java.util.Locale.ROOT);

        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid ISO 4217 currency code", exception);
        }

        return normalized;
    }
}
