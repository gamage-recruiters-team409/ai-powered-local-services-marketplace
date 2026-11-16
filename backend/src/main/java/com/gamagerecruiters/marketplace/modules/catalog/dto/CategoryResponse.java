package com.gamagerecruiters.marketplace.modules.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CategoryResponse(
        String id,
        String name,
        String slug,
        String description,
        String icon,
        String parentId,
        BigDecimal commissionRate,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}

