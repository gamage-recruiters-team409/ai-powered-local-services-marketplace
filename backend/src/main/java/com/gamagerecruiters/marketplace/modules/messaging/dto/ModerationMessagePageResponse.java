package com.gamagerecruiters.marketplace.modules.messaging.dto;

import java.util.List;

public record ModerationMessagePageResponse(
        List<ModerationMessageResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
