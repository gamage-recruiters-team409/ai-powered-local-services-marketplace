package com.gamagerecruiters.marketplace.modules.search.dto;

import java.math.BigDecimal;

import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

public record PublicPricingResponse(
        BigDecimal amount,
        String model,
        Integer durationMinutes,
        String currency,
        String unit
) {
    public static PublicPricingResponse from(
            BigDecimal amount,
            PricingModel model,
            Integer durationMinutes
    ) {
        return new PublicPricingResponse(
                amount,
                model == null ? null : model.name(),
                durationMinutes,
                null,
                null
        );
    }
}
