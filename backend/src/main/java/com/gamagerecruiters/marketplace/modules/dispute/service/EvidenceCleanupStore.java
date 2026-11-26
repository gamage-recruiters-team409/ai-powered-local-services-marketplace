package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Durable Fallback Buffer.</b> When MongoDB is temporarily unavailable during task insertion, the task is safely
 * retained in a thread-safe fallback buffer ({@link #enqueueFallback}) and logged. When database availability is
 * restored, {@link #drainFallback()} flushes all buffered tasks into MongoDB so no asset permanently escapes recovery
 * (dispute finding F6-C).</p>
 *
 * <p><b>Reference check.</b> {@link #isReferenced} looks for any dispute whose committed evidence points at the asset.
 * The reconciler calls it before deleting, so an asset whose uncertain write actually committed is never removed.</p>
 */
@Component
public class EvidenceCleanupStore {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCleanupStore.class);

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
    private final ConcurrentMap<String, EvidenceCleanupTask> fallbackQueue = new ConcurrentHashMap<>();

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
     * backing-off task. If the MongoDB insertion fails, the task is automatically retained in the durable fallback
     * buffer ({@link #enqueueFallback}) before the exception is propagated.
     */
    public void enqueue(String disputeId, String evidenceId, String publicId, EvidenceCleanupTask.Reason reason,
            Instant now) {
        Objects.requireNonNull(publicId, "publicId");
        try {
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
        } catch (RuntimeException e) {
            enqueueFallback(disputeId, evidenceId, publicId, reason, now);
            throw e;
        }
    }

    /**
     * Retains a cleanup task in the fallback buffer so that even if MongoDB was unreachable during upload, the asset
     * remains discoverable for reconciliation once database connectivity returns. De-duplicated by {@code publicId}.
     */
    public void enqueueFallback(String disputeId, String evidenceId, String publicId,
            EvidenceCleanupTask.Reason reason, Instant now) {
        Objects.requireNonNull(publicId, "publicId");
        EvidenceCleanupTask task = new EvidenceCleanupTask();
        task.setPublicId(publicId);
        task.setDisputeId(disputeId);
        task.setEvidenceId(evidenceId);
        task.setReason(reason);
        task.setCreatedAt(now);
        task.setAttempts(0);
        task.setNextAttemptAt(now);
        task.setExhausted(false);
        fallbackQueue.putIfAbsent(publicId, task);
        log.warn("event=DISPUTE_EVIDENCE_CLEANUP_FALLBACK_RETAINED disputeId={} evidenceId={} publicId={} reason={}",
                disputeId, evidenceId, publicId, reason);
    }

    /**
     * Flushes buffered fallback tasks into MongoDB. Called automatically before claiming tasks in {@link #claimDue}
     * and by {@link EvidenceCleanupReconciler}, or manually by operators. Returns the number of tasks persisted.
     */
    public int drainFallback() {
        if (fallbackQueue.isEmpty()) {
            return 0;
        }
        int drained = 0;
        for (EvidenceCleanupTask task : fallbackQueue.values()) {
            try {
                Query query = Query.query(Criteria.where(PUBLIC_ID).is(task.getPublicId()));
                Update update = new Update()
                        .setOnInsert(PUBLIC_ID, task.getPublicId())
                        .setOnInsert(DISPUTE_ID, task.getDisputeId())
                        .setOnInsert(EVIDENCE_ID, task.getEvidenceId())
                        .setOnInsert(REASON, task.getReason())
                        .setOnInsert(CREATED_AT, task.getCreatedAt())
                        .setOnInsert(ATTEMPTS, task.getAttempts())
                        .setOnInsert(NEXT_ATTEMPT_AT, task.getNextAttemptAt())
                        .setOnInsert(EXHAUSTED, task.isExhausted());
                mongoTemplate.upsert(query, update, EvidenceCleanupTask.class);
                fallbackQueue.remove(task.getPublicId());
                drained++;
                log.info("event=DISPUTE_EVIDENCE_CLEANUP_FALLBACK_DRAINED disputeId={} evidenceId={} publicId={}",
                        task.getDisputeId(), task.getEvidenceId(), task.getPublicId());
            } catch (RuntimeException e) {
                log.warn("event=DISPUTE_EVIDENCE_CLEANUP_DRAIN_FAILED cause={}", e.getClass().getSimpleName());
                break;
            }
        }
        return drained;
    }

    /** Number of tasks currently held in the fallback buffer awaiting MongoDB availability. */
    public int getFallbackCount() {
        return fallbackQueue.size();
    }

    /** Read-only snapshot of tasks currently held in the fallback buffer. */
    public List<EvidenceCleanupTask> getFallbackTasks() {
        return List.copyOf(fallbackQueue.values());
    }

    /**
     * Claims one task that is due (not exhausted, {@code nextAttemptAt <= now}, lease free or expired) and leases it.
     * Drains any pending fallback tasks into MongoDB first so newly restored tasks are immediately claimable.
     * findAndModify is atomic, so two workers never claim the same task. The returned task carries the new lease token.
     */
    public Optional<EvidenceCleanupTask> claimDue(Instant now, Duration lease) {
        drainFallback();
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

    /** Finds exhausted cleanup tasks requiring operator attention. */
    public List<EvidenceCleanupTask> findExhausted() {
        return mongoTemplate.find(Query.query(Criteria.where(EXHAUSTED).is(true)), EvidenceCleanupTask.class);
    }

    /**
     * Operator action: resets an exhausted task so it becomes immediately eligible for reconciliation again.
     */
    public boolean retryExhausted(String taskId, Instant now) {
        Query query = Query.query(Criteria.where(TASK_ID).is(taskId).and(EXHAUSTED).is(true));
        Update update = new Update()
                .set(EXHAUSTED, false)
                .set(ATTEMPTS, 0)
                .set(NEXT_ATTEMPT_AT, now)
                .unset(LEASE_UNTIL)
                .unset(LEASE_OWNER)
                .inc(VERSION, 1);
        return mongoTemplate.updateFirst(query, update, EvidenceCleanupTask.class).getModifiedCount() == 1;
    }

    /**
     * True if a cleanup task already exists for this publicId in MongoDB or in the fallback queue.
     */
    public boolean hasActiveTask(String publicId) {
        if (publicId == null) {
            return false;
        }
        if (fallbackQueue.containsKey(publicId)) {
            return true;
        }
        try {
            return mongoTemplate.exists(Query.query(Criteria.where(PUBLIC_ID).is(publicId)), EvidenceCleanupTask.class);
        } catch (RuntimeException e) {
            log.warn("event=DISPUTE_EVIDENCE_ACTIVE_TASK_CHECK_FAILED publicId={} cause={}",
                    publicId, e.getClass().getSimpleName());
            return true; // Assume active on error to prevent unsafe deletion
        }
    }
}
