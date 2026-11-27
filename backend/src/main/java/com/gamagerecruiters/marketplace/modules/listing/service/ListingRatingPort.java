package com.gamagerecruiters.marketplace.modules.listing.service;

import java.util.Optional;

/**
 * Read-only port for fetching live published listing rating aggregates from WP08 (Reviews & Ratings)
 * without coupling {@code modules/listing} directly to foreign repositories.
 */
public interface ListingRatingPort {

    Optional<ListingRatingSnapshot> findListingRating(String listingId);

    record ListingRatingSnapshot(double ratingAvg, int ratingCount) {
    }
}
