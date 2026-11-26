package com.gamagerecruiters.marketplace.modules.payment.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent;

/**
 * Repository for authenticated Stripe webhook event records.
 *
 * <p>The Stripe event ID is stored as the MongoDB document ID.
 * MongoDB's unique _id index prevents multiple documents from
 * sharing the same Stripe event identifier.</p>
 *
 * <p>Repository operations alone do not guarantee financial
 * idempotency. The processing service must coordinate event state
 * changes and payment updates safely.</p>
 */
public interface StripeWebhookEventRepository
        extends MongoRepository<StripeWebhookEvent, String> {

}