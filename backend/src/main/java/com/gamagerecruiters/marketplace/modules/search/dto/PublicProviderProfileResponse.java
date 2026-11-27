package com.gamagerecruiters.marketplace.modules.search.dto;

import java.util.List;

public record PublicProviderProfileResponse(
        String providerId,
        String profileId,
        String name,
        String image,
        boolean verified,
        Double rating,
        Long reviewCount,
        String category,
        List<String> serviceTypes,
        String description,
        String location,
        PublicPricingResponse pricing,
        Integer coverageRadiusKm,
        List<PublicProviderListingSummary> listings,
        String availabilityStatus,
        PublicProfileSupportedFlags supported,
        List<PublicProviderReviewResponse> reviews,
        String reviewContentStatus,
        Integer reviewPage,
        Integer reviewPageSize,
        Long reviewTotalElements,
        Integer reviewTotalPages
) {
    public PublicProviderProfileResponse {
        serviceTypes = serviceTypes == null ? List.of() : List.copyOf(serviceTypes);
        listings = listings == null ? List.of() : List.copyOf(listings);
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
        supported = supported == null ? PublicProfileSupportedFlags.NONE : supported;
    }

    public PublicProviderProfileResponse(
            String providerId,
            String profileId,
            String name,
            String image,
            boolean verified,
            Double rating,
            Long reviewCount,
            String category,
            List<String> serviceTypes,
            String description,
            String location,
            PublicPricingResponse pricing,
            Integer coverageRadiusKm,
            List<PublicProviderListingSummary> listings,
            String availabilityStatus,
            PublicProfileSupportedFlags supported
    ) {
        this(
                providerId,
                profileId,
                name,
                image,
                verified,
                rating,
                reviewCount,
                category,
                serviceTypes,
                description,
                location,
                pricing,
                coverageRadiusKm,
                listings,
                availabilityStatus,
                supported,
                List.of(),
                "UNAVAILABLE",
                0,
                0,
                0L,
                0
        );
    }
}
