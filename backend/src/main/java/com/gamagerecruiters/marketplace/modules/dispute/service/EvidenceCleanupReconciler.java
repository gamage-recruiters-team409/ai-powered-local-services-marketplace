package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    @Autowired
    public EvidenceCleanupReconciler(
            EvidenceCleanupStore cleanupStore,
            EvidenceStoragePort storage,
            @Value("${app.dispute.evidence-cleanup.enabled:true}") boolean enabled,
            @Value("${app.dispute.evidence-cleanup.lease:PT2M}") Duration lease,
            @Value("${app.dispute.evidence-cleanup.retry-backoff:PT5M}") Duration retryBackoff,
            @Value("${app.dispute.evidence-cleanup.max-attempts:10}") int maxAttempts,
            @Value("${app.dispute.evidence-cleanup.batch-size:50}") int batchSize) {
        this(cleanupStore, storage, Clock.systemUTC(), enabled, lease, retryBackoff, maxAttempts, batchSize);
    }

    EvidenceCleanupReconciler(EvidenceCleanupStore cleanupStore, EvidenceStoragePort storage, Clock clock,
            boolean enabled, Duration lease, Duration retryBackoff, int maxAttempts, int batchSize) {
        if (lease.isNegative() || lease.isZero() || retryBackoff.isNegative() || maxAttempts < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Invalid app.dispute.evidence-cleanup settings: need lease > 0, "
                    + "retry-backoff >= 0, max-attempts >= 1 and batch-size >= 1");
        }
        this.cleanupStore = cleanupStore;
        this.storage = storage;
        this.clock = clock;
        this.enabled = enabled;
        this.lease = lease;
        this.retryBackoff = retryBackoff;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
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

    /** One pass over at most {@code batch-size} claimable due tasks. */
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
        return new Result(kept, deleted, retried, exhausted, skipped);
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
