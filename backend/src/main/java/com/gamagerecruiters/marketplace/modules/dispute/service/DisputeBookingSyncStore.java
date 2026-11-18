package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
 * bookingSyncState = PENDING, so a record is completed or removed at most once, even when raise requests and
 * several reconciler instances race. Each write also increments the optimistic-locking version.
 *
 * <p><b>Lease.</b> Only one worker (a raise request or a reconciler run) works on a PENDING record at a time. The
 * worker holds a lease ({@code syncLeaseUntil}) tagged with its own random token ({@code syncLeaseOwner}). A lease
 * can only be taken while it is free or expired, and a record can only be removed by the worker that holds the
 * lease, so a request whose lease has expired can never remove a record that another worker has taken over.</p>
 */
@Component
public class DisputeBookingSyncStore {

    static final String FIELD_ID = "_id";
    static final String FIELD_SYNC_STATE = "bookingSyncState";
    static final String FIELD_SYNC_LEASE = "syncLeaseUntil";
    static final String FIELD_SYNC_LEASE_OWNER = "syncLeaseOwner";
    static final String FIELD_CREATED_AT = "createdAt";
    static final String FIELD_VERSION = "version";

    private final MongoTemplate mongoTemplate;

    public DisputeBookingSyncStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** A new random lease token. */
    public static String newLeaseOwner() {
        return UUID.randomUUID().toString();
    }

    private static Criteria pendingById(String disputeId) {
        return Criteria.where(FIELD_ID).is(disputeId).and(FIELD_SYNC_STATE).is(BookingSyncState.PENDING);
    }

    private static Criteria leaseFree(Instant now) {
        return new Criteria().orOperator(
                Criteria.where(FIELD_SYNC_LEASE).exists(false),
                Criteria.where(FIELD_SYNC_LEASE).is(null),
                Criteria.where(FIELD_SYNC_LEASE).lt(now));
    }

    private static Update lease(Instant now, Duration lease, String owner) {
        return new Update()
                .set(FIELD_SYNC_LEASE, now.plus(lease))
                .set(FIELD_SYNC_LEASE_OWNER, owner)
                .inc(FIELD_VERSION, 1);
    }

    /**
     * PENDING -> SYNCED, only while the caller still holds the lease. Returns the completed dispute, or empty if it is
     * no longer PENDING (completed or removed by another worker) or another worker holds the lease now.
     */
    public Optional<Dispute> markSynced(String disputeId, String leaseOwner) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Update update = new Update()
                .set(FIELD_SYNC_STATE, BookingSyncState.SYNCED)
                .unset(FIELD_SYNC_LEASE)
                .unset(FIELD_SYNC_LEASE_OWNER)
                .inc(FIELD_VERSION, 1);
        Query query = Query.query(pendingById(disputeId).and(FIELD_SYNC_LEASE_OWNER).is(leaseOwner));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Dispute.class));
    }

    /**
     * Removes the intent record only while it is still PENDING AND the caller still holds its lease. True if removed.
     * Used only after a confirmed rejection with the booking re-read as not DISPUTED (request), or a verified
     * "booking is not DISPUTED" past delete-after (reconciler).
     */
    public boolean deleteIfPending(String disputeId, String leaseOwner) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Query query = Query.query(pendingById(disputeId).and(FIELD_SYNC_LEASE_OWNER).is(leaseOwner));
        return mongoTemplate.remove(query, Dispute.class).getDeletedCount() == 1;
    }

    /**
     * Gives up the caller's lease without changing anything else, so the next retry or reconciler run can take the
     * record at once. True if the caller still held it.
     */
    public boolean releaseLease(String disputeId, String leaseOwner) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Query query = Query.query(pendingById(disputeId).and(FIELD_SYNC_LEASE_OWNER).is(leaseOwner));
        Update update = new Update().unset(FIELD_SYNC_LEASE).unset(FIELD_SYNC_LEASE_OWNER).inc(FIELD_VERSION, 1);
        return mongoTemplate.updateFirst(query, update, Dispute.class).getModifiedCount() == 1;
    }

    /**
     * A retrying raise request takes the lease of its own existing PENDING record. Empty if the record is no longer
     * PENDING or another worker (a concurrent request or the reconciler) holds an unexpired lease on it. The returned
     * record carries the new lease token.
     */
    public Optional<Dispute> claimById(String disputeId, Instant now, Duration lease) {
        Query query = Query.query(pendingById(disputeId).andOperator(leaseFree(now)));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, lease(now, lease, newLeaseOwner()),
                FindAndModifyOptions.options().returnNew(true), Dispute.class));
    }

    /**
     * Claims one PENDING record created at or before {@code createdBefore} whose lease is free, and leases it for
     * {@code lease}. findAndModify is atomic, so two workers never claim the same record at once. The returned record
     * carries the new lease token.
     */
    public Optional<Dispute> claimPending(Instant createdBefore, Instant now, Duration lease) {
        Query query = Query.query(Criteria.where(FIELD_SYNC_STATE).is(BookingSyncState.PENDING)
                .and(FIELD_CREATED_AT).lte(createdBefore)
                .andOperator(leaseFree(now)));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, lease(now, lease, newLeaseOwner()),
                FindAndModifyOptions.options().returnNew(true), Dispute.class));
    }
}
