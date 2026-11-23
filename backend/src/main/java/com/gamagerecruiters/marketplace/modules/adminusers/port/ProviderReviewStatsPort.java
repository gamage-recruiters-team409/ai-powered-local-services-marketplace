package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.util.Optional;

/** Rating of a provider from published reviews (WP08, review module). Real adapter: ReviewRatingFacade. */
public interface ProviderReviewStatsPort {

    /** Average (one decimal) and number of published reviews; 0.0 / 0 when there are none. */
    record Rating(double averageRating, long reviewCount) {
    }

    Optional<Rating> rating(String providerUserId);
}
