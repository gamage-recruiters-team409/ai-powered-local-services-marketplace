package com.gamagerecruiters.marketplace.modules.listing.dto;

/**
 * Aggregated listing counts for the Provider Listings Dashboard KPI cards and filter tabs (WP03).
 */
public record ProviderListingDashboardSummaryResponse(
        long totalListings,
        long activeCount,
        long pausedCount,
        long draftCount,
        long archivedCount,
        long flaggedCount,
        long rejectedCount,
        long categoryInactiveCount
) {
}

