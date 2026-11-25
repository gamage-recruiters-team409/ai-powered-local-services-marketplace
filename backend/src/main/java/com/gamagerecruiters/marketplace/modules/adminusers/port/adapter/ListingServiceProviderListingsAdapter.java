package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

/**
 * ProviderListingPort through ListingService's public methods, all by provider user id: getMyListingsSummary
 * (counts), getMyListings(providerUserId, null, pageable) (every status), getListingForModeration (read-only detail)
 * and updateProviderSuspensionVisibility (the suspend / reactivate cascade agreed with WP03). moderateListing is never
 * called from here.
 */
@Component
public class ListingServiceProviderListingsAdapter implements ProviderListingPort {

    private final ListingService listingService;

    public ListingServiceProviderListingsAdapter(ListingService listingService) {
        this.listingService = listingService;
    }

    @Override
    public ListingCounts counts(String providerUserId) {
        ProviderListingDashboardSummaryResponse s = listingService.getMyListingsSummary(providerUserId);
        return new ListingCounts(s.totalListings(), s.activeCount(), s.pausedCount(), s.draftCount(), s.flaggedCount(),
                s.archivedCount(), s.rejectedCount());
    }

    @Override
    public Page<ListingRow> listings(String providerUserId, Pageable pageable) {
        return listingService.getMyListings(providerUserId, null, pageable)
                .map(ListingServiceProviderListingsAdapter::toRow);
    }

    /** Read-only, any status; the caller checks that the listing belongs to the provider. */
    @Override
    public Optional<ListingDetail> listingDetail(String listingId) {
        ListingResponse l = listingService.getListingForModeration(listingId);
        String image = l.coverImageUrl() != null ? l.coverImageUrl()
                : l.images() == null || l.images().isEmpty() ? null : l.images().get(0);
        return Optional.of(new ListingDetail(l.id(), l.providerId(), l.title(), l.categoryName(), l.basePrice(),
                l.pricingModel() == null ? null : l.pricingModel().name(),
                l.status() == null ? null : l.status().name(), l.moderatedAt(), l.updatedAt(), l.moderationReason(),
                l.moderationFlags() == null ? List.of() : l.moderationFlags(), l.description(), image));
    }

    @Override
    public void updateSuspensionVisibility(String providerUserId, boolean suspended) {
        listingService.updateProviderSuspensionVisibility(providerUserId, suspended);
    }

    private static ListingRow toRow(ListingSummaryResponse l) {
        return new ListingRow(l.id(), l.title(), l.categoryName(), l.basePrice(),
                l.pricingModel() == null ? null : l.pricingModel().name(),
                l.status() == null ? null : l.status().name(),
                l.moderationFlags() == null ? List.of() : l.moderationFlags(), l.updatedAt());
    }
}
