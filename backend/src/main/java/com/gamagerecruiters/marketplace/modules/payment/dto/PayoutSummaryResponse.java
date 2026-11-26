package com.gamagerecruiters.marketplace.modules.payment.dto;

/**
 * Read-only summary of provider payout requests grouped by lifecycle status.
 *
 * <p>
 * Counts represent the complete payout collection rather than the
 * currently displayed page or active search filters.
 * </p>
 *
 * @param pending requests awaiting finance administrator processing
 * @param processing requests undergoing external bank transfer processing
 * @param paid requests with confirmed successful transfers
 * @param failed requests with recorded transfer failures
 */
public record PayoutSummaryResponse(
        long pending,
        long processing,
        long paid,
        long failed) {
}
