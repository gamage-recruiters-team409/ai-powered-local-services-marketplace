package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A durable instruction to reconcile a stored evidence asset whose database outcome is unresolved, so an uploaded
 * file can never become permanently untracked when persistence or deletion fails (dispute finding F6). It is written
 * in two situations:
 * <ul>
 *   <li><b>UNCERTAIN_WRITE</b> — the conditional record update threw and the follow-up re-read could not confirm
 *       whether the evidence was committed. The asset is preserved (never deleted on an unconfirmed read) and left for
 *       the reconciler to settle once MongoDB is reachable again.</li>
 *   <li><b>DELETE_FAILED</b> — the record was rejected (or never committed) but the best-effort storage delete threw,
 *       so the orphaned asset still needs removing.</li>
 * </ul>
 *
 * <p>The reconciler (EvidenceCleanupReconciler) always re-checks whether {@code publicId} is referenced by a
 * successfully committed evidence record before deleting: if it is, the write did commit and the asset is kept; only a
 * genuinely unreferenced asset is removed. Deletion is retried with backoff up to a bounded number of attempts; after
 * that the task is marked {@code exhausted} and left for manual intervention rather than being retried forever.</p>
 *
 * <p>One task per asset: {@code publicId} is unique, and enqueue upserts so the same asset is never queued twice.
 * Only ids are stored and logged, never URLs, file names or contents.</p>
 */
@Document(collection = "dispute_evidence_cleanup_tasks")
public class EvidenceCleanupTask {

    /** Why the asset needs reconciling. */
    public enum Reason {
        /** Record update threw and the re-read could not confirm the outcome: keep if committed, else delete. */
        UNCERTAIN_WRITE,
        /** Record was not committed but the best-effort storage delete failed: the orphan still needs removing. */
        DELETE_FAILED
    }

    @Id
    private String id;

    @Version
    private Long version;

    /** The storage reference of the asset to reconcile. Unique across tasks. */
    private String publicId;

    /** Context only (the asset is identified by publicId). */
    private String disputeId;
    private String evidenceId;

    private Reason reason;
    private Instant createdAt;

    /** How many deletion attempts have been made. */
    private int attempts;

    /** The earliest time the task may be claimed again (backoff between attempts). */
    private Instant nextAttemptAt;

    /** True once the attempt budget is spent: no longer retried, kept for manual recovery. */
    private boolean exhausted;

    /** Internal: until when one reconciler run holds this task. */
    private Instant leaseUntil;

    /** Internal: random token of the worker holding the lease; only that worker may resolve or reschedule it. */
    private String leaseOwner;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getDisputeId() {
        return disputeId;
    }

    public void setDisputeId(String disputeId) {
        this.disputeId = disputeId;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public Reason getReason() {
        return reason;
    }

    public void setReason(Reason reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public boolean isExhausted() {
        return exhausted;
    }

    public void setExhausted(boolean exhausted) {
        this.exhausted = exhausted;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }
}
