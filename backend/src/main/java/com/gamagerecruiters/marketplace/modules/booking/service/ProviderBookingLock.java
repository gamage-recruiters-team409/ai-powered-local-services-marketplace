package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Short per-provider lock so two booking requests for the same provider are never checked and
 * saved at the same time (prevents double booking across threads and app instances).
 *
 * <p>One document per provider in {@code booking_provider_locks} ({@code _id} = providerId).
 * Acquire is a single atomic upsert that only matches an expired lock; if the lock is held, the
 * upsert tries to insert the same {@code _id} and MongoDB rejects it (duplicate key) → not acquired.
 * The lease expires automatically, so a crashed request cannot block the provider for long.
 */
@Component
public class ProviderBookingLock {

    static final String COLLECTION = "booking_provider_locks";
    static final Duration LEASE = Duration.ofSeconds(10);

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public ProviderBookingLock(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC());
    }

    ProviderBookingLock(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /** Returns an owner token when the lock was acquired, or {@code null} when another request holds it. */
    public String tryAcquire(String providerId) {
        Instant now = Instant.now(clock);
        String owner = UUID.randomUUID().toString();
        Query freeOrExpired = Query.query(Criteria.where("_id").is(providerId)
                .and("lockedUntil").lt(Date.from(now)));
        Update take = new Update()
                .set("owner", owner)
                .set("lockedUntil", Date.from(now.plus(LEASE)));
        try {
            mongoTemplate.upsert(freeOrExpired, take, COLLECTION);
            return owner;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** Releases the lock only if this request still owns it. */
    public void release(String providerId, String owner) {
        if (owner == null) {
            return;
        }
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(providerId).and("owner").is(owner)), COLLECTION);
    }
}
