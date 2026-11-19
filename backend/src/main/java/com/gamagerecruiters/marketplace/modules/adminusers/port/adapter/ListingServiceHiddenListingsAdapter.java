package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

/**
 * ProviderListingPort through ListingService: getMyListingsSummary (counts) and getMyListings(FLAGGED) for the most
 * recent flagged listings. Both take the provider's user id as a parameter and are read-only.
 */
@Component
public class ListingServiceHiddenListingsAdapter implements ProviderListingPort {

    private final ListingService listingService;

    public ListingServiceHiddenListingsAdapter(ListingService listingService) {
        this.listingService = listingService;
    }

    @Override
    public Optional<HiddenListings> hiddenListings(String providerUserId, int limit) {
        ProviderListingDashboardSummaryResponse summary = listingService.getMyListingsSummary(providerUserId);
        List<HiddenListing> flagged = summary.flaggedCount() == 0 ? List.of()
                : listingService.getMyListings(providerUserId, ListingStatus.FLAGGED, PageRequest.of(0, limit))
                        .getContent().stream()
                        .map(ListingServiceHiddenListingsAdapter::toHidden)
                        .toList();
        return Optional.of(new HiddenListings(summary.flaggedCount(), summary.rejectedCount(), summary.pausedCount(),
                flagged));
    }

    private static HiddenListing toHidden(ListingSummaryResponse l) {
        return new HiddenListing(l.id(), l.title(), l.status() == null ? null : l.status().name(),
                l.moderationFlags() == null ? List.of() : l.moderationFlags(), l.createdAt());
    }
}
