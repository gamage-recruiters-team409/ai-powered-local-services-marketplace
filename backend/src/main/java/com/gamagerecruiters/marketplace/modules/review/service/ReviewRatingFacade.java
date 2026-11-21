package com.gamagerecruiters.marketplace.modules.review.service;

import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;

/**
 * Public read API of the stored rating aggregates (FR-REV-004) for other modules:
 * provider profile (WP03), search/comparison (WP04), AI ranking and analytics.
 * Values come from PUBLISHED reviews only and are kept up to date by the review module.
 */
public interface ReviewRatingFacade {

    /** Average (one decimal) and count for a provider; 0.0 / 0 when it has no published reviews. */
    RatingAggregate getProviderRating(String providerId);

    /** Average (one decimal) and count for a listing; 0.0 / 0 when it has no published reviews. */
    RatingAggregate getListingRating(String listingId);
}
