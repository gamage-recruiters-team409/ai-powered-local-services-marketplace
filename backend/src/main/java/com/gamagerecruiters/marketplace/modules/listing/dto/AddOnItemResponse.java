package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;

public record AddOnItemResponse(
        String addOnId,
        String title,
        String description,
        BigDecimal extraPrice,
        Integer extraDurationMin,
        boolean active
) {
    public AddOnItemResponse(
            String addOnId,
            String title,
            BigDecimal extraPrice,
            Integer extraDurationMin,
            boolean active
    ) {
        this(addOnId, title, null, extraPrice, extraDurationMin, active);
    }
}
