package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

public record ListingSummaryResponse(
        String id,
        String providerId,
        String categoryId,
        String categoryName,
        String title,
        PricingModel pricingModel,
        BigDecimal basePrice,
        Integer durationMin,
        ExecutionModel executionModel,
        String district,
        String baseCity,
        String coverImageUrl,
        ListingStatus status,
        boolean availableForBooking,
        boolean categoryActive,
        int activeAddOnCount,
        List<String> moderationFlags,
        double ratingAvg,
        int ratingCount,
        Instant createdAt,
        Instant updatedAt
) {
    public ListingSummaryResponse {
        moderationFlags = moderationFlags == null ? List.of() : List.copyOf(moderationFlags);
    }

    public ListingSummaryResponse(
            String id,
            String providerId,
            String categoryId,
            String categoryName,
            String title,
            PricingModel pricingModel,
            BigDecimal basePrice,
            Integer durationMin,
            ExecutionModel executionModel,
            String district,
            String baseCity,
            String coverImageUrl,
            ListingStatus status,
            boolean availableForBooking,
            List<String> moderationFlags,
            double ratingAvg,
            int ratingCount,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                providerId,
                categoryId,
                categoryName,
                title,
                pricingModel,
                basePrice,
                durationMin,
                executionModel,
                district,
                baseCity,
                coverImageUrl,
                status,
                availableForBooking,
                true,
                0,
                moderationFlags,
                ratingAvg,
                ratingCount,
                createdAt,
                updatedAt
        );
    }
}
