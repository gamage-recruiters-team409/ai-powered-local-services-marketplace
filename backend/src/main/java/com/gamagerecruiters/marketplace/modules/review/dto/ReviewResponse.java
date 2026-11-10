package com.gamagerecruiters.marketplace.modules.review.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

public record ReviewResponse(
        String id,
        String bookingId,
        String listingId,
        String providerId,
        int rating,
        String comment,
        ReviewStatus status,
        ProviderReplyResponse providerReply,
        boolean edited,
        Instant editedAt,
        Instant createdAt
) {
}
