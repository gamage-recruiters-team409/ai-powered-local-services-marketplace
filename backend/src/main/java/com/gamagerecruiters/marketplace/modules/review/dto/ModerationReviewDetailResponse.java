package com.gamagerecruiters.marketplace.modules.review.dto;

import java.util.List;

/** S15 – the review, its open reports and its moderation history. */
public record ModerationReviewDetailResponse(
        ModerationReviewResponse review,
        List<ReviewReportResponse> openReports,
        List<ModerationEntryResponse> history
) {
}
