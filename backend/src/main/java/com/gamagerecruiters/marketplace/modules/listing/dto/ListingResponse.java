package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

public record ListingResponse(
        String id,
        String providerId,
        String categoryId,
        String categoryName,
        String parentCategoryId,
        String title,
        String description,
        PricingModel pricingModel,
        BigDecimal basePrice,
        Integer durationMin,
        ExecutionModel executionModel,
        String district,
        String baseCity,
        GeoPointDto location,
        Integer coverageRadiusKm,
        List<String> serviceAreas,
        List<String> images,
        String coverImageUrl,
        List<AddOnItemResponse> addOns,
        ListingStatus status,
        boolean availableForBooking,
        boolean categoryActive,
        int activeAddOnCount,
        List<String> moderationFlags,
        String moderationReason,
        Instant moderatedAt,
        double ratingAvg,
        int ratingCount,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public ListingResponse {
        serviceAreas = serviceAreas == null ? List.of() : List.copyOf(serviceAreas);
        images = images == null ? List.of() : List.copyOf(images);
        addOns = addOns == null ? List.of() : List.copyOf(addOns);
        moderationFlags = moderationFlags == null ? List.of() : List.copyOf(moderationFlags);
    }

    public ListingResponse(
            String id,
            String providerId,
            String categoryId,
            String categoryName,
            String parentCategoryId,
            String title,
            String description,
            PricingModel pricingModel,
            BigDecimal basePrice,
            Integer durationMin,
            ExecutionModel executionModel,
            String district,
            String baseCity,
            GeoPointDto location,
            Integer coverageRadiusKm,
            List<String> serviceAreas,
            List<String> images,
            String coverImageUrl,
            List<AddOnItemResponse> addOns,
            ListingStatus status,
            boolean availableForBooking,
            List<String> moderationFlags,
            String moderationReason,
            Instant moderatedAt,
            double ratingAvg,
            int ratingCount,
            Long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                providerId,
                categoryId,
                categoryName,
                parentCategoryId,
                title,
                description,
                pricingModel,
                basePrice,
                durationMin,
                executionModel,
                district,
                baseCity,
                location,
                coverageRadiusKm,
                serviceAreas,
                images,
                coverImageUrl,
                addOns,
                status,
                availableForBooking,
                true,
                addOns == null ? 0 : (int) addOns.stream().filter(AddOnItemResponse::active).count(),
                moderationFlags,
                moderationReason,
                moderatedAt,
                ratingAvg,
                ratingCount,
                version,
                createdAt,
                updatedAt
        );
    }
}
