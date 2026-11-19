package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

/**
 * Immutable price and commission snapshot captured at booking time (WP05 / WP07).
 *
 * <p>{@code basePrice} represents the price for the full configured {@code baseDurationMin}
 * duration under both {@link PricingModel#FIXED} and {@link PricingModel#HOURLY}; {@code pricingModel}
 * is included alongside {@code baseDurationMin} so WP05/WP07 can coordinate any future hourly scaling
 * if variable-duration bookings are introduced.
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
