
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

import org.springframework.stereotype.Service;

/**
 * Creates validated payment records with customer and commission snapshots.
 *
 * <p>
 * This service prepares payment data only. It does not persist records,
 * authorize bookings, or communicate with a payment gateway.
 * </p>
 *
 * <p>
 * The customer identifier must be obtained from a trusted booking
 * authorization workflow before calling this service. The service
 * validates the identifier but does not independently verify ownership.
 * </p>
 */
@Service
public class PaymentCreationService {

    private final CommissionCalculator commissionCalculator;

    /**
     * Creates the service with its commission calculator dependency.
     *
     * @param commissionCalculator calculator used for commission amounts
     */
    public PaymentCreationService(CommissionCalculator commissionCalculator) {
        this.commissionCalculator = Objects.requireNonNull(
                commissionCalculator,
                "Commission calculator must not be null");
    }

    /**
     * Prepares a payment with customer ownership and commission snapshots.
     *
     * <p>
     * The customer ID must come from verified BookingFacade information,
     * not directly from client-supplied request data.
     * </p>
     *
     * @param bookingId      identifier of the associated booking
     * @param customerId     verified customer identifier from BookingFacade
     * @param amount         positive payment amount
     * @param currency       ISO 4217 currency code
     * @param commissionRate commission rate represented as a fraction
     * @return initialized payment record, not yet persisted
     * @throws IllegalArgumentException if payment details are invalid
     */
    public Payment createPayment(
            String bookingId,
            String customerId,
            BigDecimal amount,
            String currency,
            BigDecimal commissionRate) {

        validateBookingId(bookingId);
        validateCustomerId(customerId);
        validateAmount(amount);

        String normalizedCurrency = validateCurrency(currency);

        // Calculate and capture the commission snapshot at creation time.
        BigDecimal commissionAmount = commissionCalculator
                .calculateCommission(amount, commissionRate);

        Instant now = Instant.now();

        Payment payment = new Payment();

        payment.setBookingId(bookingId.trim());

        // The caller must have verified this customer against BookingFacade.
        payment.setCustomerId(customerId.trim());

        payment.setAmount(amount);
        payment.setCurrency(normalizedCurrency);
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setRefundedAmount(BigDecimal.ZERO.setScale(2));

        // Preserve the applicable commission rate and calculated amount.
        payment.setCommissionRate(commissionRate);
        payment.setCommissionAmount(commissionAmount);

        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);

        return payment;
    }

    /**
     * Rejects missing or blank booking identifiers.
     *
     * @param bookingId booking identifier to validate
     */
    private void validateBookingId(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException(
                    "Booking ID must not be blank");
        }
    }

    /**
     * Rejects missing or blank customer identifiers.
     *
     * <p>
     * This validation does not replace BookingFacade ownership checks.
     * The calling workflow is responsible for supplying a verified ID.
     * </p>
     *
     * @param customerId verified customer identifier
     */
    private void validateCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Customer ID must not be blank");
        }
    }

    /**
     * Requires a positive monetary amount with at most two decimals.
     *
     * @param amount payment amount to validate
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
     *
     * @param currency currency code to validate
     * @return normalized uppercase currency code
     */
    private String validateCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "Currency must not be blank");
        }

        String normalized = currency.trim().toUpperCase(Locale.ROOT);

        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid ISO 4217 currency code",
                    exception);
        }

        return normalized;
    }
}
