package com.gamagerecruiters.marketplace.modules.search.dto;

public record ProviderSearchResult(
        String providerId,
        String profileId,
        String listingId,
        String name,
        String image,
        boolean verified,
        Double rating,
        Long reviewCount,
        String category,
        String description,
        String location,
        PublicPricingResponse pricing,
        Integer coverageRadiusKm,
        SearchSupportedFlags supported
) {
    public ProviderSearchResult {
        pricing = pricing == null ? new PublicPricingResponse(null, null, null, null, null) : pricing;
        supported = supported == null ? SearchSupportedFlags.NONE : supported;
    }
}
