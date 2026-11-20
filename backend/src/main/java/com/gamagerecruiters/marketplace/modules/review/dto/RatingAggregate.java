package com.gamagerecruiters.marketplace.modules.review.dto;

/**
 * Stored rating of a provider or a listing for other modules (FR-REV-004):
 * average rounded to one decimal (0.0 when there are no published reviews) and the number of published reviews.
 */
public record RatingAggregate(
        String subjectId,
        double ratingAvg,
        long ratingCount
) {
}
