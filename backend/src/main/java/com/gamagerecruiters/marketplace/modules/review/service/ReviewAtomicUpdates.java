package com.gamagerecruiters.marketplace.modules.review.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

/**
 * Atomic conditional updates for reviews (concurrency protection).
 *
 * <p>Each method is one MongoDB {@code findAndModify}: the business conditions are part of the
 * query, and only the fields that the action owns are written ({@code $set}). So a customer edit
 * and a provider reply sent at the same time can never overwrite each other's fields, and a stale
 * edit cannot slip in after the provider has replied. No new field or migration is needed.
 */
@Component
public class ReviewAtomicUpdates {

    private final MongoTemplate mongoTemplate;

    public ReviewAtomicUpdates(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Applies a customer edit only if, at that moment, the review still belongs to the customer,
     * is not REMOVED, has no provider reply, and was created at or after {@code editableFrom}
     * (a review without createdAt never matches).
     *
     * @return the updated review, or empty when any condition no longer holds
     */
    public Optional<Review> applyCustomerEdit(String reviewId, String customerId, int rating, String comment,
                                              Instant editableFrom, Instant now) {
        Query query = new Query(Criteria.where("id").is(reviewId)
                .and("customerId").is(customerId)
                .and("status").ne(ReviewStatus.REMOVED.name())
                .and("providerReply").is(null)
                .and("createdAt").gte(editableFrom));

        Update update = new Update()
                .set("rating", rating)
                .set("edited", true)
                .set("editedAt", now)
                .set("updatedAt", now);
        if (comment == null) {
            update.unset("comment");
        } else {
            update.set("comment", comment);
        }

        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Review.class));
    }

    /**
     * Adds or replaces the provider's single reply only if the review still belongs to the
     * provider and is PUBLISHED. Only {@code providerReply} and {@code updatedAt} are written,
     * so the customer's rating and comment are never touched.
     *
     * @return the updated review, or empty when any condition no longer holds
     */
    public Optional<Review> applyProviderReply(String reviewId, String providerId, ProviderReply reply, Instant now) {
        Query query = new Query(Criteria.where("id").is(reviewId)
                .and("providerId").is(providerId)
                .and("status").is(ReviewStatus.PUBLISHED.name()));

        Update update = new Update()
                .set("providerReply", reply)
                .set("updatedAt", now);

        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Review.class));
    }
}
