package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

/** ProviderReviewStatsPort through ReviewService.getRatingSummary (published reviews only). */
@Component
public class ReviewServiceRatingAdapter implements ProviderReviewStatsPort {

    private final ReviewService reviewService;

    public ReviewServiceRatingAdapter(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public Optional<Rating> rating(String providerUserId) {
        RatingSummaryResponse summary = reviewService.getRatingSummary(providerUserId);
        return Optional.of(new Rating(summary.averageRating(), summary.reviewCount(), summary.ratingCounts()));
    }
}
