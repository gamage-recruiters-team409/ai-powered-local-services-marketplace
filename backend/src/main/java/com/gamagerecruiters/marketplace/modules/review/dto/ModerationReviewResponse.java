package com.gamagerecruiters.marketplace.modules.review.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

/** A review as the moderator sees it (S14 queue row, S15 detail). */
public record ModerationReviewResponse(
        String id,
        String bookingId,
        String listingId,
        String providerId,
        String providerName,
        String customerId,
        String customerName,
        int rating,
        String comment,
        ReviewStatus status,
        List<ModerationFlag> moderationFlags,
        int openReportCount,
        boolean edited,
        ProviderReplyResponse providerReply,
        String removalReason,
        Instant createdAt
) {
}
