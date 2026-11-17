package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

/**
 * Immutable price and commission snapshot captured at booking time (WP05 / WP07).
 */
public record PriceSnapshotResponse(
        String listingId,
        String providerId,
        String categoryId,
        String title,
        PricingModel pricingModel,
        BigDecimal basePrice,
        Integer baseDurationMin,
        BigDecimal commissionRate,
        List<AddOnItemResponse> selectedAddOns,
        BigDecimal totalPrice,
        int totalDurationMin,
        Instant capturedAt
) {
    public PriceSnapshotResponse {
        selectedAddOns = selectedAddOns == null ? List.of() : List.copyOf(selectedAddOns);
    }
}
