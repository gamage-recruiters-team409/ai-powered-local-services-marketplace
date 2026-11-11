package com.gamagerecruiters.marketplace.modules.review.dto;

import java.util.Map;

/**
 * Provider rating from published reviews only.
 * averageRating is rounded to one decimal place (0.0 when there are no reviews);
 * ratingCounts has an entry for every star value 1–5.
 */
public record RatingSummaryResponse(
        String providerId,
        double averageRating,
        long reviewCount,
        Map<Integer, Long> ratingCounts
) {
}
