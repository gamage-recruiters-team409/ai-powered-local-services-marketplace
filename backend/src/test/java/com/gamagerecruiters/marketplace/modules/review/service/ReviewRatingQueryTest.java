package com.gamagerecruiters.marketplace.modules.review.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;

class ReviewRatingQueryTest {

    @Test
    @DisplayName("Batch summaries use one aggregation grouped by providerId and rating for PUBLISHED reviews")
    void batchesPublishedSummariesWithOneAggregation() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ReviewRatingQuery query = new ReviewRatingQuery(mongoTemplate);

        Document keyFive = new Document("providerId", "prov-1").append("rating", 5);
        Document keyFour = new Document("providerId", "prov-1").append("rating", 4);
        List<Document> rows = List.of(
                new Document("_id", keyFive).append("count", 2),
                new Document("_id", keyFour).append("count", 1));

        @SuppressWarnings("unchecked")
        AggregationResults<Document> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(rows);
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(Review.class), eq(Document.class)))
                .thenReturn(results);

        Map<String, RatingSummaryResponse> summaries =
                query.getRatingSummaries(List.of("prov-1", "prov-2"));

        assertEquals(2, summaries.size());
        assertEquals(3L, summaries.get("prov-1").reviewCount());
        assertEquals(4.7, summaries.get("prov-1").averageRating());
        assertEquals(0L, summaries.get("prov-2").reviewCount());

        ArgumentCaptor<Aggregation> captor = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(captor.capture(), eq(Review.class), eq(Document.class));
        String pipeline = captor.getValue().toString();
        assertTrue(pipeline.contains("providerId"));
        assertTrue(pipeline.contains("rating"));
        assertTrue(pipeline.contains("PUBLISHED"));
    }

    @Test
    @DisplayName("Empty provider id collection skips the aggregation")
    void skipsAggregationForEmptyInput() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ReviewRatingQuery query = new ReviewRatingQuery(mongoTemplate);

        assertTrue(query.getRatingSummaries(List.of()).isEmpty());
        verify(mongoTemplate, never()).aggregate(any(Aggregation.class), eq(Review.class), eq(Document.class));
    }
}
