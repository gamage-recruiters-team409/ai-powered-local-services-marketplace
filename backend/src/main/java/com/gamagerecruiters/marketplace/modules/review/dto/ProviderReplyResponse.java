package com.gamagerecruiters.marketplace.modules.review.dto;

import java.time.Instant;

public record ProviderReplyResponse(
        String text,
        Instant repliedAt
) {
}
