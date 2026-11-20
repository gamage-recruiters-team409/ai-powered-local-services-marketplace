package com.gamagerecruiters.marketplace.modules.review.service;

import java.util.HashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

/**
 * Counts PUBLISHED reviews per star value with one MongoDB aggregation, instead of loading every review.
 * Provider counts use the providerId+status+createdAt index; listing counts the listingId+status index.
 */
@Component
public class ReviewRatingQuery {

    private final MongoTemplate mongoTemplate;

    public ReviewRatingQuery(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Star value (1–5) -> number of the provider's published reviews with that rating. */
    public Map<Integer, Long> countPublishedByRating(String providerId) {
        return countPublished("providerId", providerId);
    }

    /** Star value (1–5) -> number of the listing's published reviews with that rating (FR-REV-004). */
    public Map<Integer, Long> countPublishedByRatingForListing(String listingId) {
        return countPublished("listingId", listingId);
    }

    private Map<Integer, Long> countPublished(String field, String value) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where(field).is(value)
                        .and("status").is(ReviewStatus.PUBLISHED.name())),
                Aggregation.group("rating").count().as("count"));

        Map<Integer, Long> counts = new HashMap<>();
        for (Document row : mongoTemplate.aggregate(aggregation, Review.class, Document.class).getMappedResults()) {
            Object rating = row.get("_id");
            Object count = row.get("count");
            if (rating instanceof Number r && count instanceof Number c) {
                counts.merge(r.intValue(), c.longValue(), Long::sum);
            }
        }
        return counts;
    }
}
