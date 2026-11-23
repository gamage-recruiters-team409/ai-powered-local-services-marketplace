package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingFacade;

/** ProviderReviewStatsPort through ReviewRatingFacade.getProviderRating (stored aggregate, published reviews only). */
@Component
public class ReviewServiceRatingAdapter implements ProviderReviewStatsPort {

    private final ReviewRatingFacade ratingFacade;

    public ReviewServiceRatingAdapter(ReviewRatingFacade ratingFacade) {
        this.ratingFacade = ratingFacade;
    }

    @Override
    public Optional<Rating> rating(String providerUserId) {
        RatingAggregate aggregate = ratingFacade.getProviderRating(providerUserId);
        return Optional.of(new Rating(aggregate.ratingAvg(), aggregate.ratingCount()));
    }
}
