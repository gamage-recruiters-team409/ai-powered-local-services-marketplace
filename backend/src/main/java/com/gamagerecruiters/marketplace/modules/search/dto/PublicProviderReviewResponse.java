package com.gamagerecruiters.marketplace.modules.search.dto;

public record PublicProviderReviewResponse(
        String id,
        int rating,
        String comment,
        String createdAt,
        boolean edited,
        String editedAt,
        PublicProviderReplyResponse providerReply
) {
    public record PublicProviderReplyResponse(
            String text,
            String repliedAt
    ) {
    }
}
