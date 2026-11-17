package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;

/**
 * Atomic, conditional updates of the internal booking-sync state. Every write is filtered on
 * bookingSyncState = PENDING, so a record is completed or removed at most once, even when the request thread and
 * several reconciler instances race. Each write also increments the optimistic-locking version.
 */
@Component
public class DisputeBookingSyncStore {

    static final String FIELD_ID = "_id";
    static final String FIELD_SYNC_STATE = "bookingSyncState";
    static final String FIELD_SYNC_LEASE = "syncLeaseUntil";
    static final String FIELD_CREATED_AT = "createdAt";
    static final String FIELD_VERSION = "version";

    private final MongoTemplate mongoTemplate;

    public DisputeBookingSyncStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private static Query pendingById(String disputeId) {
        return Query.query(Criteria.where(FIELD_ID).is(disputeId).and(FIELD_SYNC_STATE).is(BookingSyncState.PENDING));
    }

    /**
     * PENDING -> SYNCED. Returns the completed dispute, or empty if it is no longer PENDING (already completed by
     * another thread or instance, or removed).
     */
    public Optional<Dispute> markSynced(String disputeId) {
        Update update = new Update()
                .set(FIELD_SYNC_STATE, BookingSyncState.SYNCED)
                .unset(FIELD_SYNC_LEASE)
                .inc(FIELD_VERSION, 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(pendingById(disputeId), update,
                FindAndModifyOptions.options().returnNew(true), Dispute.class));
    }

    /** Compensation: removes the intent record only while it is still PENDING. True if it was removed. */
    public boolean deleteIfPending(String disputeId) {
        return mongoTemplate.remove(pendingById(disputeId), Dispute.class).getDeletedCount() == 1;
    }

    /**
     * Claims one PENDING record created at or before {@code createdBefore} whose lease is free, and leases it for
     * {@code lease}. findAndModify is atomic, so two reconciler instances never claim the same record at once.
     */
    public Optional<Dispute> claimPending(Instant createdBefore, Instant now, Duration lease) {
        Criteria leaseFree = new Criteria().orOperator(
                Criteria.where(FIELD_SYNC_LEASE).exists(false),
                Criteria.where(FIELD_SYNC_LEASE).is(null),
                Criteria.where(FIELD_SYNC_LEASE).lt(now));
        Query query = Query.query(Criteria.where(FIELD_SYNC_STATE).is(BookingSyncState.PENDING)
                .and(FIELD_CREATED_AT).lte(createdBefore)
                .andOperator(leaseFree));
        Update update = new Update().set(FIELD_SYNC_LEASE, now.plus(lease)).inc(FIELD_VERSION, 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Dispute.class));
    }
}
