package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Financial breakdown of a recognized provider earning.
 *
 * Original commission values are taken from the persisted payment
 * snapshot. Refund adjustments represent the provider's share of
 * successful refunds, not the total customer refund amount.
 *
 * Net earnings represent financial entitlement, not the amount
 * currently available for withdrawal.
 */
public record ItemizedEarningsResponse(
        String paymentId,
        String bookingId,
        BigDecimal grossAmount,
        BigDecimal commissionRate,
        BigDecimal commissionAmount,
        BigDecimal refundedAmount,
        BigDecimal refundAdjustmentAmount,
        BigDecimal netEarnings,
        String currency,
        Instant recognizedAt) {
}
