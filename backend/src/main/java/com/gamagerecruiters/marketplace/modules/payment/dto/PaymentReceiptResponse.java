package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * Customer-facing receipt for a verified payment capture.
 *
 * <p>Exposes the original captured amount and verified capture
 * timestamp without exposing internal gateway references,
 * commission details, or provider financial information.</p>
 *
 * @param receiptReference stable receipt identifier
 * @param paymentId associated payment identifier
 * @param bookingId associated booking identifier
 * @param amount original captured payment amount
 * @param currency payment currency
 * @param paidAt verified capture timestamp
 */
public record PaymentReceiptResponse(
        String receiptReference,
        String paymentId,
        String bookingId,
        BigDecimal amount,
        String currency,
        Instant paidAt) {

    /**
     * Maps an authorized payment with verified receipt metadata.
     *
     * <p>Ownership and receipt eligibility must be validated
     * by the retrieval service before invoking this method.</p>
     *
     * @param payment authorized and receipt-eligible payment
     * @return customer-facing receipt
     */
    public static PaymentReceiptResponse from(Payment payment) {
        Objects.requireNonNull(payment, "Payment must not be null");

        return new PaymentReceiptResponse(
                payment.getReceiptReference(),
                payment.getId(),
                payment.getBookingId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaidAt());
    }
}
