package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;

/**
 * Admin dashboard counts. The first three are current counts; {@code resolvedLast30Days} counts admin decisions made
 * since {@code resolvedSince} (whatever the dispute's status is now); {@code manualReview} counts final disputes whose
 * settlement needs an admin (MANUAL_REVIEW or REJECTED). PENDING sync records are never counted.
 */
public record DisputeAdminSummaryResponse(
        long underReview,
        long awaitingResponse,
        long appealed,
        long resolvedLast30Days,
        Instant resolvedSince,
        long manualReview
) {
}
