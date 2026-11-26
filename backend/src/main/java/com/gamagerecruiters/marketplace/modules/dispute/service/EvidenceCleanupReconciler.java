package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceCleanupTask;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

/**
 * Settles evidence-cleanup tasks ({@link EvidenceCleanupTask}) left behind when an uploaded Cloudinary asset's
 * database outcome could not be resolved inline — an uncertain record write, or a best-effort delete that failed
 * (dispute finding F6). A task is never resolved by a log line alone: every asset is reconciled against the real
 * committed state before anything is removed.
 *
 * <p>For each due task:</p>
 * <ul>
 *   <li><b>Referenced</b> — a dispute holds a committed evidence record for the asset, so the write did persist. The
 *       asset is kept and the task removed.</li>
 *   <li><b>Not referenced</b> — the asset is a genuine orphan. Deletion is attempted; on success the task is removed,
 *       on failure it is retried later with a fixed backoff up to {@code max-attempts}, after which it is marked
 *       exhausted and left for manual recovery rather than retried forever.</li>
 * </ul>
 *
 * <p>Claiming is an atomic lease, so this is safe on several instances. Only ids are logged.</p>
 *
 * <p>Settings (defaults in brackets): {@code app.dispute.evidence-cleanup.enabled} [true], {@code .interval} [PT5M],
 * {@code .initial-delay} [PT1M], {@code .lease} [PT2M], {@code .retry-backoff} [PT5M], {@code .max-attempts} [10],
 * {@code .batch-size} [50].</p>
 */
@Component
public class EvidenceCleanupReconciler {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCleanupReconciler.class);

    /** What one run did. */
    public record Result(int kept, int deleted, int retried, int exhausted, int skipped) {
    }

    private final EvidenceCleanupStore cleanupStore;
    private final EvidenceStoragePort storage;
    private final Clock clock;
    private final boolean enabled;
    private final Duration lease;
    private final Duration retryBackoff;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration inventoryMinAge;

    @Autowired
    public EvidenceCleanupReconciler(
            EvidenceCleanupStore cleanupStore,
            EvidenceStoragePort storage,
            @Value("${app.dispute.evidence-cleanup.enabled:true}") boolean enabled,
            @Value("${app.dispute.evidence-cleanup.lease:PT2M}") Duration lease,
            @Value("${app.dispute.evidence-cleanup.retry-backoff:PT5M}") Duration retryBackoff,
            @Value("${app.dispute.evidence-cleanup.max-attempts:10}") int maxAttempts,
            @Value("${app.dispute.evidence-cleanup.batch-size:50}") int batchSize,
            @Value("${app.dispute.evidence-cleanup.inventory-min-age:PT30M}") Duration inventoryMinAge) {
        this(cleanupStore, storage, Clock.systemUTC(), enabled, lease, retryBackoff, maxAttempts, batchSize,
                inventoryMinAge);
    }

    EvidenceCleanupReconciler(EvidenceCleanupStore cleanupStore, EvidenceStoragePort storage, Clock clock,
            boolean enabled, Duration lease, Duration retryBackoff, int maxAttempts, int batchSize,
            Duration inventoryMinAge) {
        if (lease.isNegative() || lease.isZero() || retryBackoff.isNegative() || maxAttempts < 1 || batchSize < 1
                || inventoryMinAge.isNegative()) {
            throw new IllegalArgumentException("Invalid app.dispute.evidence-cleanup settings: need lease > 0, "
                    + "retry-backoff >= 0, max-attempts >= 1, batch-size >= 1 and inventory-min-age >= 0");
        }
        this.cleanupStore = cleanupStore;
        this.storage = storage;
        this.clock = clock;
        this.enabled = enabled;
        this.lease = lease;
        this.retryBackoff = retryBackoff;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.inventoryMinAge = inventoryMinAge;
    }

    /** Runs only when scheduling is enabled (DisputeSchedulingConfig) and the reconciler is not switched off. */
    @Scheduled(fixedDelayString = "${app.dispute.evidence-cleanup.interval:PT5M}",
            initialDelayString = "${app.dispute.evidence-cleanup.initial-delay:PT1M}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            Result result = reconcile();
            if (result.kept() + result.deleted() + result.retried() + result.exhausted() + result.skipped() > 0) {
                log.info("event=DISPUTE_EVIDENCE_CLEANUP_RECONCILED kept={} deleted={} retried={} exhausted={} "
                        + "skipped={}", result.kept(), result.deleted(), result.retried(), result.exhausted(),
                        result.skipped());
            }
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_EVIDENCE_CLEANUP_RECONCILE_FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    /** One pass over at most {@code batch-size} claimable due tasks and storage inventory reconciliation. */
    public Result reconcile() {
        cleanupStore.drainFallback();
        int kept = 0;
        int deleted = 0;
        int retried = 0;
        int exhausted = 0;
        int skipped = 0;
        for (int i = 0; i < batchSize; i++) {
            Optional<EvidenceCleanupTask> claimed = cleanupStore.claimDue(clock.instant(), lease);
            if (claimed.isEmpty()) {
                break;
            }
            switch (reconcileOne(claimed.get())) {
                case KEPT -> kept++;
                case DELETED -> deleted++;
                case RETRIED -> retried++;
                case EXHAUSTED -> exhausted++;
                default -> skipped++;
            }
        }

        Result inventoryResult = reconcileInventory();
        kept += inventoryResult.kept();
        deleted += inventoryResult.deleted();
        retried += inventoryResult.retried();

        return new Result(kept, deleted, retried, exhausted, skipped);
    }

    /**
     * Storage-inventory reconciliation (finding F6-C): directly inspects storage inventory under {@code disputes/}
     * to discover and recover any untracked assets whose cleanup-task insertion failed across application restarts
     * or process-state loss. Never deletes an asset if its reference outcome cannot be confirmed (e.g. MongoDB outage).
     *
     * <p>A freshly uploaded asset is deliberately left alone until it is older than {@code inventory-min-age}: in the
     * brief window between a successful Cloudinary upload and the matching evidence write committing, the asset reads
     * as unreferenced even though it is a valid, still-in-flight upload. Skipping assets younger than the grace period
     * (and any asset whose creation time storage did not report) keeps the reference-safety rule intact.</p>
     */
    public Result reconcileInventory() {
        List<EvidenceStoragePort.StorageAsset> storageAssets;
        try {
            storageAssets = storage.listAssets("disputes/");
        } catch (RuntimeException e) {
            log.warn("event=DISPUTE_EVIDENCE_INVENTORY_LIST_FAILED cause={}", e.getClass().getSimpleName());
            return new Result(0, 0, 0, 0, 0);
        }

        if (storageAssets == null || storageAssets.isEmpty()) {
            return new Result(0, 0, 0, 0, 0);
        }

        int kept = 0;
        int deleted = 0;
        int retried = 0;
        Instant ageCutoff = clock.instant().minus(inventoryMinAge);

        for (EvidenceStoragePort.StorageAsset asset : storageAssets) {
            String publicId = asset.publicId();

            // Grace period: never act on an asset whose upload may still be committing, nor one of unknown age.
            if (asset.createdAt() == null || asset.createdAt().isAfter(ageCutoff)) {
                continue;
            }

            boolean referenced;
            try {
                referenced = cleanupStore.isReferenced(publicId);
            } catch (RuntimeException e) {
                // MongoDB unavailable: outcome is unknown. NEVER delete when outcome cannot be confirmed!
                log.warn("event=DISPUTE_EVIDENCE_INVENTORY_CHECK_FAILED publicId={} cause={}",
                        publicId, e.getClass().getSimpleName());
                continue;
            }

            if (referenced) {
                // Committed evidence: preserve it safely
                continue;
            }

            // Asset is NOT referenced in committed evidence. Check if already tracked by an active task.
            if (cleanupStore.hasActiveTask(publicId)) {
                // Already managed by scheduled task queue
                continue;
            }

            // Confirmed unreferenced orphan: safe to delete
            try {
                storage.delete(publicId);
                deleted++;
                log.info("event=DISPUTE_EVIDENCE_INVENTORY_ORPHAN_DELETED publicId={}", publicId);
            } catch (RuntimeException e) {
                log.warn("event=DISPUTE_EVIDENCE_INVENTORY_DELETE_FAILED publicId={} cause={}",
                        publicId, e.getClass().getSimpleName());
                try {
                    cleanupStore.enqueue(null, null, publicId, EvidenceCleanupTask.Reason.DELETE_FAILED, clock.instant());
                    retried++;
                } catch (RuntimeException ex) {
                    // Safe to ignore: next inventory reconciliation run will rediscover it
                }
            }
        }

        return new Result(kept, deleted, retried, 0, 0);
    }

    private enum Outcome { KEPT, DELETED, RETRIED, EXHAUSTED, SKIPPED }

    private Outcome reconcileOne(EvidenceCleanupTask task) {
        String publicId = task.getPublicId();
        String leaseOwner = task.getLeaseOwner();

        // The write may have committed after all: an asset that a dispute still references must never be deleted.
        if (cleanupStore.isReferenced(publicId)) {
            if (cleanupStore.resolve(task.getId(), leaseOwner)) {
                log.info("event=DISPUTE_EVIDENCE_CLEANUP_KEPT disputeId={} evidenceId={} publicId={}",
                        task.getDisputeId(), task.getEvidenceId(), publicId);
                return Outcome.KEPT;
            }
            return Outcome.SKIPPED;
        }

        try {
            storage.delete(publicId);
        } catch (RuntimeException e) {
            if (task.getAttempts() + 1 >= maxAttempts) {
                cleanupStore.markExhausted(task.getId(), leaseOwner);
                log.error("event=DISPUTE_EVIDENCE_CLEANUP_EXHAUSTED disputeId={} evidenceId={} publicId={} "
                        + "attempts={} cause={}", task.getDisputeId(), task.getEvidenceId(), publicId,
                        task.getAttempts() + 1, e.getClass().getSimpleName());
                return Outcome.EXHAUSTED;
            }
            cleanupStore.reschedule(task.getId(), leaseOwner, clock.instant().plus(retryBackoff));
            log.warn("event=DISPUTE_EVIDENCE_CLEANUP_RETRY disputeId={} evidenceId={} publicId={} attempts={} cause={}",
                    task.getDisputeId(), task.getEvidenceId(), publicId, task.getAttempts() + 1,
                    e.getClass().getSimpleName());
            return Outcome.RETRIED;
        }

        if (cleanupStore.resolve(task.getId(), leaseOwner)) {
            log.info("event=DISPUTE_EVIDENCE_CLEANUP_DELETED disputeId={} evidenceId={} publicId={}",
                    task.getDisputeId(), task.getEvidenceId(), publicId);
            return Outcome.DELETED;
        }
        return Outcome.SKIPPED;
    }
}
