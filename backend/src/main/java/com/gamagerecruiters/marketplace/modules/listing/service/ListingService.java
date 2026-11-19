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
     * Public &amp; Customer lookup contract (WP01 / FR-CUST-005): returns {@code ACTIVE} plus
     * ordinary provider-{@code PAUSED} listings (marked unavailable), and hides suspension-hidden
     * listings ({@code hiddenByProviderSuspension = true}, BR-010) plus {@code DRAFT}/{@code ARCHIVED}/
     * {@code FLAGGED}/{@code REJECTED} unless the authenticated viewer is the owning provider or a
     * moderator/super-admin.
     */
    ListingResponse getListingById(String id);

    /**
     * Batch contract agreed with Customer module (FR-CUST-005) to load favourites in a single query.
     * Returns only publicly readable statuses ({@code ACTIVE} and ordinary {@code PAUSED}),
     * excluding suspension-hidden listings ({@code hiddenByProviderSuspension = true}, BR-010).
     */
    List<ListingResponse> getListingsByIds(Collection<String> ids);

    ListingResponse getListingForViewer(String id, String currentUserIdOrNull, Role currentRoleOrNull);

    ListingResponse updateListing(String providerUserId, String id, UpdateListingRequest request);

    ListingResponse updateListingStatus(String providerUserId, String id, UpdateListingStatusRequest request);

    ListingResponse getListingForModeration(String id);

    Page<ListingSummaryResponse> getModerationQueue(ListingStatus status, Pageable pageable);

    ListingResponse moderateListing(String moderatorUserId, String id, ListingModerationRequest request);

    /**
     * Captures a read-only price, duration, and category commission snapshot for booking creation (FR-LIST-005)
     * after verifying listing status, active category hierarchy, and live provider eligibility ({@code ACTIVE} account
     * and WP02 {@code APPROVED} verification).
     * Both {@code FIXED} and {@code HOURLY} listings treat {@code basePrice} as covering the listing's
     * configured {@code baseDurationMin}, returning {@code totalPrice = basePrice + sum(selectedAddOns.extraPrice)}
     * and {@code totalDurationMin = baseDurationMin + sum(selectedAddOns.extraDurationMin)} unless a variable
     * duration-multiplier contract is later agreed with WP05/WP07.
     */
    PriceSnapshotResponse getListingPriceSnapshot(String listingId, List<String> selectedAddOnIds);

    /**
     * Cascades provider suspension/recovery visibility across listings (BR-010).
     * When {@code suspended} is {@code true}, {@code ACTIVE} listings become {@code PAUSED} with
     * {@code hiddenByProviderSuspension = true}. When {@code suspended} is {@code false}, previously
     * suspension-hidden listings are restored to {@code ACTIVE} only if the provider is currently eligible
     * ({@code ACTIVE} account and WP02 {@code APPROVED} verification); otherwise they remain {@code PAUSED} and hidden.
     */
    void updateProviderSuspensionVisibility(String providerId, boolean suspended);

    void updateListingRatingStats(String listingId, double ratingAvg, int ratingCount);

    boolean hasBlockingListingsForCategory(String categoryId);

    List<ProvinceGeoDto> getDistrictLocations();
}
