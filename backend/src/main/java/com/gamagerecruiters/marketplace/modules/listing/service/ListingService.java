package com.gamagerecruiters.marketplace.modules.listing.service;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.CreateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingModerationRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.PriceSnapshotResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingStatusRequest;

/**
 * Public service contract for WP03 Service Listings, including cross-module contracts
 * for Customer Favourites (FR-CUST-005), Booking Price Snapshots (WP05), Reviews (WP08),
 * Catalog checks (FR-LIST-001), and Provider suspension cascades (BR-010).
 */
public interface ListingService {

    ListingResponse createListing(String providerUserId, CreateListingRequest request);

    default Page<ListingSummaryResponse> getMyListings(String providerUserId, ListingStatus status, Pageable pageable) {
        return getMyListings(providerUserId, status, null, pageable);
    }

    Page<ListingSummaryResponse> getMyListings(String providerUserId, ListingStatus status, String search, Pageable pageable);

    ProviderListingDashboardSummaryResponse getMyListingsSummary(String providerUserId);

    /**
     * Exact contract promised to Customer module (FR-CUST-005): returns ACTIVE plus PAUSED/ARCHIVED
     * read-only state cards, and hides DRAFT/FLAGGED/REJECTED unless viewer is owner or moderator/super-admin.
     */
    ListingResponse getListingById(String id);

    /**
     * Batch contract agreed with Customer module (FR-CUST-005) to load favourites in a single query.
     * Returns only customer-readable statuses ({@code ACTIVE}, {@code PAUSED}, {@code ARCHIVED}).
     */
    List<ListingResponse> getListingsByIds(Collection<String> ids);

    ListingResponse getListingForViewer(String id, String currentUserIdOrNull, Role currentRoleOrNull);

    ListingResponse updateListing(String providerUserId, String id, UpdateListingRequest request);

    ListingResponse updateListingStatus(String providerUserId, String id, UpdateListingStatusRequest request);

    ListingResponse getListingForModeration(String id);

    Page<ListingSummaryResponse> getModerationQueue(ListingStatus status, Pageable pageable);

    ListingResponse moderateListing(String moderatorUserId, String id, ListingModerationRequest request);

    PriceSnapshotResponse getListingPriceSnapshot(String listingId, List<String> selectedAddOnIds);

    void updateProviderSuspensionVisibility(String providerId, boolean suspended);

    void updateListingRatingStats(String listingId, double ratingAvg, int ratingCount);

    boolean hasBlockingListingsForCategory(String categoryId);

    List<ProvinceGeoDto> getDistrictLocations();
}
