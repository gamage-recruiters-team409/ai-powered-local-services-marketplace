package com.gamagerecruiters.marketplace.modules.review.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

/**
 * A review as returned to customers and providers.
 * customerName: filled for the provider's own list (S11), null elsewhere.
 * removalReason: the moderator's reason, only when status is REMOVED (shown to the customer on S09).
 */
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
        Instant createdAt,
        String customerName,
        String removalReason
) {
}
