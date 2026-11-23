package com.gamagerecruiters.marketplace.modules.dispute.domain;

/**
 * Whether the final decision has been carried out in another module (finance: release/refund/penalty; booking: final
 * status). Used for each step and for the overall settlement. The decision itself is never changed or removed
 * because of this state.
 */
public enum SettlementState {
    /** Final, not attempted yet. */
    PENDING,
    /**
     * Attempted, but the integration is not connected yet (stub): NOTHING was applied. Not a failure and not counted
     * as an attempt; the recovery job re-checks it at its normal interval.
     */
    NOT_APPLIED,
    /** Confirmed as applied by the real integration (only ever set when the port returned APPLIED). */
    APPLIED,
    /** Attempted and failed, or the outcome is uncertain. Counted as an attempt and retried with backoff. */
    FAILED,
    /** Permanently rejected by the finance integration (SettlementRejectedException). Terminal: never retried. */
    REJECTED,
    /** Needs an admin: see {@code manualReviewReason}. Terminal for the recovery job: never retried automatically. */
    MANUAL_REVIEW
}
