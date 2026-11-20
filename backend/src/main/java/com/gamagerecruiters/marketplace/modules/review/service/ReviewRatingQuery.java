package com.gamagerecruiters.marketplace.modules.review.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;

/**
 * Counts a provider's PUBLISHED reviews per star value with one MongoDB aggregation
 * (uses the providerId+status+createdAt index), instead of loading every review.
 */
@Component
public class ReviewRatingQuery {

    private final MongoTemplate mongoTemplate;

    public ReviewRatingQuery(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Map<String, RatingSummaryResponse> getRatingSummaries(Collection<String> providerIds) {
        Map<String, RatingSummaryResponse> summaries = new LinkedHashMap<>();
        if (providerIds == null || providerIds.isEmpty()) {
            return summaries;
        }

        Set<String> ids = new LinkedHashSet<>();
        for (String providerId : providerIds) {
            if (providerId != null && !providerId.isBlank()) {
                ids.add(providerId);
            }
        }
        if (ids.isEmpty()) {
            return summaries;
        }
        Map<String, Map<Integer, Long>> countsByProvider = new LinkedHashMap<>();
        for (String providerId : ids) {
            countsByProvider.put(providerId, new HashMap<>());
        }

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("providerId").in(ids)
                        .and("status").is(ReviewStatus.PUBLISHED.name())),
                Aggregation.group("providerId", "rating").count().as("count"));

        for (Document row : mongoTemplate.aggregate(aggregation, Review.class, Document.class).getMappedResults()) {
            Document groupKey = row.get("_id") instanceof Document doc ? doc : null;
            if (groupKey == null || !(groupKey.get("providerId") instanceof String providerId)) {
                continue;
            }
            Map<Integer, Long> counts = countsByProvider.get(providerId);
            Object rating = groupKey.get("rating");
            Object count = row.get("count");
            if (counts != null && rating instanceof Number r && count instanceof Number c) {
                counts.merge(r.intValue(), c.longValue(), Long::sum);
            }
        }

        countsByProvider.forEach((providerId, counts) ->
                summaries.put(providerId, ReviewServiceImpl.summarize(providerId, counts)));
        return summaries;
    }

    /** Star value (1–5) -> number of published reviews with that rating. */
    public Map<Integer, Long> countPublishedByRating(String providerId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("providerId").is(providerId)
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
