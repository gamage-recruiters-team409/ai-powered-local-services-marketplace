package com.gamagerecruiters.marketplace.modules.customer.service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

/**
 * Adapter implementing {@link FavouriteListingLookup} using the listing module (FR-CUST-005).
 * Resolves listing existence, title, category, location, cover image, rating, review count,
 * pricing, and active/paused/booking status.
 *
 * <p>Single batched query per request without N+1 queries. Missing or non-readable listings
 * are omitted from the result map. Non-ACTIVE statuses (e.g. PAUSED) or listings not available
 * for booking are marked {@code inactive = true}.
 */
@Component
public class FavouriteListingLookupAdapter implements FavouriteListingLookup {

    private static final String DEFAULT_CURRENCY = "LKR";

    private final ListingService listingService;

    public FavouriteListingLookupAdapter(ListingService listingService) {
        this.listingService = listingService;
    }

    @Override
    public Optional<FavouriteTargetDetails> lookup(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return Optional.empty();
        }
        Map<String, FavouriteTargetDetails> batch = lookupBatch(List.of(targetId));
        return Optional.ofNullable(batch.get(targetId));
    }

    @Override
    public Map<String, FavouriteTargetDetails> lookupBatch(Collection<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // ONE batched getListingsByIds call per request (no N+1).
        // If ListingService throws, let it propagate so fail-closed 503 handling applies.
        List<ListingResponse> listings = listingService.getListingsByIds(targetIds);
        if (listings == null || listings.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, FavouriteTargetDetails> result = new HashMap<>();
        for (ListingResponse listing : listings) {
            if (listing == null || listing.id() == null) {
                continue;
            }

            // Non-ACTIVE statuses (e.g. PAUSED) or unavailable for booking are inactive
            boolean inactive = listing.status() != ListingStatus.ACTIVE || !listing.availableForBooking();

            // Location: baseCity preferred, fallback to district
            String location = listing.baseCity() != null && !listing.baseCity().isBlank()
                    ? listing.baseCity()
                    : listing.district();

            Double rating = listing.ratingAvg() > 0.0 ? listing.ratingAvg() : null;
            Integer reviewCount = listing.ratingCount() > 0 ? listing.ratingCount() : null;
            String priceUnit = listing.pricingModel() != null ? listing.pricingModel().name() : null;

            FavouriteTargetDetails details = new FavouriteTargetDetails(
                    listing.id(),
                    listing.title(),
                    null,
                    listing.categoryName(),
                    location,
                    listing.coverImageUrl(),
                    rating,
                    reviewCount,
                    listing.basePrice(),
                    priceUnit,
                    DEFAULT_CURRENCY,
                    inactive
            );

            result.put(listing.id(), details);
        }

        return result;
    }
}
