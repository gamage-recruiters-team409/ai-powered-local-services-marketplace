package com.gamagerecruiters.marketplace.modules.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.review.domain.ReviewRatingStats;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReviewRatingStatsServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsFacadeRatingFieldsForAPublishedProviderAggregate() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ReviewRatingQuery ratingQuery = mock(ReviewRatingQuery.class);
        when(mongoTemplate.findById(anyString(), org.mockito.ArgumentMatchers.eq(ReviewRatingStats.class)))
                .thenReturn(null);
        when(ratingQuery.countPublishedByRating("provider-1"))
                .thenReturn(Map.of(5, 2L, 3, 1L));
        ReviewRatingStatsService service = new ReviewRatingStatsService(mongoTemplate, ratingQuery, CLOCK);

        RatingAggregate aggregate = service.getProviderRating("provider-1");

        assertThat(aggregate.subjectId()).isEqualTo("provider-1");
        assertThat(aggregate.ratingAvg()).isEqualTo(4.3);
        assertThat(aggregate.ratingCount()).isEqualTo(3L);
    }

    @Test
    void reportsZeroAverageAndZeroCountForAProviderWithNoPublishedReviews() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ReviewRatingQuery ratingQuery = mock(ReviewRatingQuery.class);
        when(mongoTemplate.findById(anyString(), org.mockito.ArgumentMatchers.eq(ReviewRatingStats.class)))
                .thenReturn(null);
        when(ratingQuery.countPublishedByRating("provider-empty"))
                .thenReturn(Map.of());
        ReviewRatingStatsService service = new ReviewRatingStatsService(mongoTemplate, ratingQuery, CLOCK);

        RatingAggregate aggregate = service.getProviderRating("provider-empty");

        assertThat(aggregate.subjectId()).isEqualTo("provider-empty");
        assertThat(aggregate.ratingAvg()).isZero();
        assertThat(aggregate.ratingCount()).isZero();
    }
}
