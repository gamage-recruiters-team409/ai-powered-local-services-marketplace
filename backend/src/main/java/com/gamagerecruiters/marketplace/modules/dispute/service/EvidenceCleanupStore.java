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

import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceCleanupTask;

/**
 * Durable storage for evidence-cleanup tasks (see {@link EvidenceCleanupTask}). Writes are atomic and lease-guarded so
 * several reconciler instances never work the same task at once.
 *
 * <p><b>One task per asset.</b> {@link #enqueue} upserts on {@code publicId}: a new task is inserted, while an existing
 * one keeps its attempt count and schedule, so retries from the upload path never reset a backing-off task or create a
 * duplicate. A unique index on {@code publicId} (DisputeIndexInitializer) backs this.</p>
 *
 * <p><b>Reference check.</b> {@link #isReferenced} looks for any dispute whose committed evidence points at the asset.
 * The reconciler calls it before deleting, so an asset whose uncertain write actually committed is never removed.</p>
 */
@Component
public class EvidenceCleanupStore {

    static final String TASK_ID = "_id";
    static final String PUBLIC_ID = "publicId";
    static final String ATTEMPTS = "attempts";
    static final String NEXT_ATTEMPT_AT = "nextAttemptAt";
    static final String EXHAUSTED = "exhausted";
    static final String LEASE_UNTIL = "leaseUntil";
    static final String LEASE_OWNER = "leaseOwner";
    static final String CREATED_AT = "createdAt";
    static final String DISPUTE_ID = "disputeId";
    static final String EVIDENCE_ID = "evidenceId";
    static final String REASON = "reason";
    static final String VERSION = "version";

    static final String EVIDENCE_PUBLIC_ID = "evidenceFiles.publicId";

    private final MongoTemplate mongoTemplate;

    public EvidenceCleanupStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** A new random lease token. */
    public static String newLeaseOwner() {
        return UUID.randomUUID().toString();
    }

    /**
     * Records (or leaves in place) a cleanup task for {@code publicId}, due immediately. Upserts on {@code publicId}:
     * fields of an existing task that is still in flight are not reset, so a retried upload never disturbs a
     * backing-off task. Never throws out of the upload path — a failure here is logged by the caller.
     */
    public void enqueue(String disputeId, String evidenceId, String publicId, EvidenceCleanupTask.Reason reason,
            Instant now) {
        Objects.requireNonNull(publicId, "publicId");
        Query query = Query.query(Criteria.where(PUBLIC_ID).is(publicId));
        Update update = new Update()
                .setOnInsert(PUBLIC_ID, publicId)
                .setOnInsert(DISPUTE_ID, disputeId)
                .setOnInsert(EVIDENCE_ID, evidenceId)
                .setOnInsert(REASON, reason)
                .setOnInsert(CREATED_AT, now)
                .setOnInsert(ATTEMPTS, 0)
                .setOnInsert(NEXT_ATTEMPT_AT, now)
                .setOnInsert(EXHAUSTED, false);
        mongoTemplate.upsert(query, update, EvidenceCleanupTask.class);
    }

    /**
     * Claims one task that is due (not exhausted, {@code nextAttemptAt <= now}, lease free or expired) and leases it.
     * findAndModify is atomic, so two workers never claim the same task. The returned task carries the new lease token.
     */
    public Optional<EvidenceCleanupTask> claimDue(Instant now, Duration lease) {
        Criteria leaseFree = new Criteria().orOperator(
                Criteria.where(LEASE_UNTIL).exists(false),
                Criteria.where(LEASE_UNTIL).is(null),
                Criteria.where(LEASE_UNTIL).lt(now));
        Query query = Query.query(Criteria.where(EXHAUSTED).ne(true)
                .and(NEXT_ATTEMPT_AT).lte(now)
                .andOperator(leaseFree));
        String owner = newLeaseOwner();
        Update update = new Update()
                .set(LEASE_UNTIL, now.plus(lease))
                .set(LEASE_OWNER, owner)
                .inc(VERSION, 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), EvidenceCleanupTask.class));
    }

    /** True if any dispute holds a committed evidence record for {@code publicId} (i.e. the write did persist). */
    public boolean isReferenced(String publicId) {
        return mongoTemplate.exists(Query.query(Criteria.where(EVIDENCE_PUBLIC_ID).is(publicId)), Dispute.class);
    }

    /** Removes the task, only while the caller still holds its lease. True if removed. */
    public boolean resolve(String taskId, String leaseOwner) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Query query = Query.query(Criteria.where(TASK_ID).is(taskId).and(LEASE_OWNER).is(leaseOwner));
        return mongoTemplate.remove(query, EvidenceCleanupTask.class).getDeletedCount() == 1;
    }

    /**
     * Records one more failed attempt and defers the next try, releasing the lease, only while the caller still holds
     * it. True if updated.
     */
    public boolean reschedule(String taskId, String leaseOwner, Instant nextAttemptAt) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Query query = Query.query(Criteria.where(TASK_ID).is(taskId).and(LEASE_OWNER).is(leaseOwner));
        Update update = new Update()
                .inc(ATTEMPTS, 1)
                .set(NEXT_ATTEMPT_AT, nextAttemptAt)
                .unset(LEASE_UNTIL)
                .unset(LEASE_OWNER)
                .inc(VERSION, 1);
        return mongoTemplate.updateFirst(query, update, EvidenceCleanupTask.class).getModifiedCount() == 1;
    }

    /**
     * Marks the task exhausted so it is never retried again (kept for manual recovery), only while the caller still
     * holds its lease. True if updated.
     */
    public boolean markExhausted(String taskId, String leaseOwner) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Query query = Query.query(Criteria.where(TASK_ID).is(taskId).and(LEASE_OWNER).is(leaseOwner));
        Update update = new Update()
                .inc(ATTEMPTS, 1)
                .set(EXHAUSTED, true)
                .unset(LEASE_UNTIL)
                .unset(LEASE_OWNER)
                .inc(VERSION, 1);
        return mongoTemplate.updateFirst(query, update, EvidenceCleanupTask.class).getModifiedCount() == 1;
    }
}
