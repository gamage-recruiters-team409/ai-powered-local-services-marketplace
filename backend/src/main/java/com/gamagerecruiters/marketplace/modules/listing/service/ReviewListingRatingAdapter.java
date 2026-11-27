package com.gamagerecruiters.marketplace.modules.listing.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingFacade;

/**
 * WP03 adapter reading live published listing rating aggregates from WP08's {@link ReviewRatingFacade}
 * (PR #41). Falls back gracefully to stored listing stats if the review facade is unavailable.
 */
@Component
public class ReviewListingRatingAdapter implements ListingRatingPort {

    private static final Logger log = LoggerFactory.getLogger(ReviewListingRatingAdapter.class);

    private final ObjectProvider<ReviewRatingFacade> reviewRatingFacadeProvider;

    public ReviewListingRatingAdapter(ObjectProvider<ReviewRatingFacade> reviewRatingFacadeProvider) {
        this.reviewRatingFacadeProvider = reviewRatingFacadeProvider;
    }

    @Override
    public Optional<ListingRatingSnapshot> findListingRating(String listingId) {
        if (listingId == null || listingId.isBlank() || reviewRatingFacadeProvider == null) {
            return Optional.empty();
        }
        ReviewRatingFacade facade = reviewRatingFacadeProvider.getIfAvailable();
        if (facade == null) {
            return Optional.empty();
        }
        try {
            RatingAggregate aggregate = facade.getListingRating(listingId);
            if (aggregate == null) {
                return Optional.empty();
            }
            double safeAvg = Double.isFinite(aggregate.ratingAvg())
                    ? Math.max(0.0, Math.min(5.0, aggregate.ratingAvg()))
                    : 0.0;
            int safeCount = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, aggregate.ratingCount()));
            return Optional.of(new ListingRatingSnapshot(safeAvg, safeCount));
        } catch (RuntimeException ex) {
            log.warn("event=LISTING_RATING_LOOKUP_FAILED listingId={} cause={}",
                    listingId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
