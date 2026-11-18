
package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Customer-facing payment response.
 *
 * <p>
 * Exposes only the payment information required by customers.
 * Internal gateway references, commission calculations, and other
 * persistence details are intentionally excluded.
 * </p>
 *
 * @param paymentId      unique payment identifier
 * @param bookingId      associated booking identifier
 * @param amount         total payment amount
 * @param currency       payment currency
 * @param status         current payment status
 * @param refundedAmount total amount refunded
 * @param createdAt      payment creation timestamp
 */
public record PaymentResponse(
        String paymentId,
        String bookingId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        BigDecimal refundedAmount,
        Instant createdAt) {

    /**
     * Converts a payment entity into a customer-facing response.
     *
     * <p>
     * This method performs data mapping only. Authorization must
     * be completed before the payment entity is passed here.
     * </p>
     *
     * @param payment authorized payment entity
     * @return customer-facing payment response
     * @throws NullPointerException if payment is null
     */
    public static PaymentResponse from(Payment payment) {
        Objects.requireNonNull(
                payment,
                "Payment must not be null");

        return new PaymentResponse(
                payment.getId(),
                payment.getBookingId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getRefundedAmount(),
                payment.getCreatedAt());
    }
}
