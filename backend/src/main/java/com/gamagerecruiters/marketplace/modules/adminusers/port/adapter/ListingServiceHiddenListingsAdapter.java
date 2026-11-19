package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
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

    /** Read-only, through ListingService.getListingForModeration (any status; the caller checks the provider). */
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

    /**
     * Not supported yet. Listing moderation belongs to the MODERATOR area (the listing module's moderation endpoint
     * is canEditArea(MODERATOR)) and its decisions are APPROVE (ACTIVE) / REJECT (REJECTED), with no "keep hidden".
     * An ADMIN decision needs a listing facade method agreed with the listing owner and the TL.
     */
    @Override
    public boolean supportsListingReview() {
        return false;
    }

    @Override
    public void reviewListing(String actorId, String listingId, ReviewDecision decision, String note) {
        throw new UnsupportedOperationException("Admin listing review is not connected to the listing module yet");
    }

    private static HiddenListing toHidden(ListingSummaryResponse l) {
        return new HiddenListing(l.id(), l.title(), l.status() == null ? null : l.status().name(),
                l.moderationFlags() == null ? List.of() : l.moderationFlags(), l.createdAt());
    }
}
