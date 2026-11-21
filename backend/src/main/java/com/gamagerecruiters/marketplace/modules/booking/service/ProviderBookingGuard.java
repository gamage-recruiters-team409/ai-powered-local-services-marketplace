package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Instant;
import java.util.Date;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.mongodb.client.result.UpdateResult;

/**
 * Serialisation point for booking creation, used inside a MongoDB transaction.
 *
 * <p>Every booking-creation transaction first writes the provider's guard document in
 * {@code booking_provider_guards} ({@code _id} = providerId, version incremented). Two
 * transactions for the same provider therefore write the same document: MongoDB lets only one
 * of them proceed and aborts the other with a WriteConflict (TransientTransactionError). The
 * aborted one retries with a fresh snapshot and then sees the committed booking.
 *
 * <p>There is no lease or timeout: however long the first transaction takes between the slot
 * check and the insert, the second cannot commit an overlapping booking.
 *
 * <p>The guard document is created by {@link #ensureExists} outside the transaction, so two
 * first-ever requests for a provider never race on inserting it inside their transactions.
 */
@Component
public class ProviderBookingGuard {

    static final String COLLECTION = "booking_provider_guards";

    private final MongoTemplate mongoTemplate;

    public ProviderBookingGuard(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Called before the transaction starts: creates the provider's guard document if missing. */
    public void ensureExists(String providerId, Instant now) {
        mongoTemplate.upsert(
                Query.query(Criteria.where("_id").is(providerId)),
                new Update().setOnInsert("version", 0L).setOnInsert("createdAt", Date.from(now)),
                COLLECTION);
    }

    /** Must be called inside the booking-creation transaction, before the slot re-check. */
    public void claim(String providerId, Instant now) {
        UpdateResult result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(providerId)),
                new Update().inc("version", 1).set("updatedAt", Date.from(now)),
                COLLECTION);
        if (result.getMatchedCount() != 1) {
            throw new IllegalStateException("Booking guard document missing for provider " + providerId);
        }
    }
}
