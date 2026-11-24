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
        PublicProfileSupportedFlags supported
) {
    public PublicProviderProfileResponse {
        serviceTypes = serviceTypes == null ? List.of() : List.copyOf(serviceTypes);
        listings = listings == null ? List.of() : List.copyOf(listings);
        supported = supported == null ? PublicProfileSupportedFlags.NONE : supported;
    }
}
