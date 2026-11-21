package com.gamagerecruiters.marketplace.modules.review.dto;

import java.util.List;

/** One page of the moderation queue (S14). */
public record ModerationPageResponse(
        List<ModerationReviewResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
