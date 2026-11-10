package com.gamagerecruiters.marketplace.modules.availability.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;

public record BlockedDateResponse(
        String id,
        String date,
        BlockReason reason,
        String note,
        Instant createdAt
) {
}
