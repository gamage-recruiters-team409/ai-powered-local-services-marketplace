package com.gamagerecruiters.marketplace.modules.search.dto;

import java.util.List;

public record PublicProviderListingSummary(
        String id,
        String title,
        String description,
        String categoryName,
        PublicPricingResponse pricing,
        String image,
        Integer coverageRadiusKm,
        List<String> serviceAreas
) {
    public PublicProviderListingSummary {
        serviceAreas = serviceAreas == null ? List.of() : List.copyOf(serviceAreas);
    }
}
