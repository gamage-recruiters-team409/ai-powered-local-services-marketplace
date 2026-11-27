package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.gamagerecruiters.marketplace.modules.listing.service.ListingRatingPort.ListingRatingSnapshot;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingFacade;

class ReviewListingRatingAdapterTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<ReviewRatingFacade> providerOf(ReviewRatingFacade facade) {
        ObjectProvider<ReviewRatingFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        return provider;
    }

    @Test
    @DisplayName("Maps live RatingAggregate from ReviewRatingFacade into ListingRatingSnapshot")
    void mapsLiveRatingAggregate() {
        ReviewRatingFacade facade = mock(ReviewRatingFacade.class);
        when(facade.getListingRating("lst-1")).thenReturn(new RatingAggregate("lst-1", 4.8, 27L));

        ReviewListingRatingAdapter adapter = new ReviewListingRatingAdapter(providerOf(facade));
        Optional<ListingRatingSnapshot> result = adapter.findListingRating("lst-1");

        assertThat(result).contains(new ListingRatingSnapshot(4.8, 27));
    }

    @Test
    @DisplayName("Returns empty gracefully when facade is missing, blank id, or throws RuntimeException")
    void fallsBackGracefullyOnUnavailableOrFailure() {
        ReviewListingRatingAdapter emptyAdapter = new ReviewListingRatingAdapter(providerOf(null));
        assertThat(emptyAdapter.findListingRating("lst-1")).isEmpty();
        assertThat(emptyAdapter.findListingRating("  ")).isEmpty();

        ReviewRatingFacade failingFacade = mock(ReviewRatingFacade.class);
        when(failingFacade.getListingRating("lst-2")).thenThrow(new IllegalStateException("DB hiccup"));
        ReviewListingRatingAdapter failingAdapter = new ReviewListingRatingAdapter(providerOf(failingFacade));
        assertThat(failingAdapter.findListingRating("lst-2")).isEmpty();
    }
}
