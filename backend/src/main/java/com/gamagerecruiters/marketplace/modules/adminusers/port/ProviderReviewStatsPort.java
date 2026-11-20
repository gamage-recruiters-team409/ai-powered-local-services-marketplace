package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.util.Map;
import java.util.Optional;

/** Rating of a provider from published reviews (WP08, review module). Real adapter: ReviewService. */
public interface ProviderReviewStatsPort {

    /** {@code ratingCounts}: stars (5..1) to number of published reviews. */
    record Rating(double averageRating, long reviewCount, Map<Integer, Long> ratingCounts) {
    }

    Optional<Rating> rating(String providerUserId);
}
