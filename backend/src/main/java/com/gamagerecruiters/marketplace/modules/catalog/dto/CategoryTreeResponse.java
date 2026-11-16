package com.gamagerecruiters.marketplace.modules.catalog.dto;

import java.math.BigDecimal;
import java.util.List;

public record CategoryTreeResponse(
        String id,
        String name,
        String slug,
        String description,
        String icon,
        BigDecimal commissionRate,
        boolean active,
        List<CategoryResponse> subcategories
) {
}

