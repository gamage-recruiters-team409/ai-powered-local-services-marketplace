package com.gamagerecruiters.marketplace.modules.dispute.domain;

/**
 * Whether the final decision has been carried out in another module (booking status, payout/refund/penalty).
 * The decision itself is never changed or removed because of this state.
 */
public enum SettlementState {
    /** Final, not attempted yet. */
    PENDING,
    /** Attempted, but no real integration exists yet (stub): NOTHING was applied. Not retried automatically. */
    NOT_APPLIED,
    /** Confirmed as applied by the real integration. */
    APPLIED,
    /** Attempted and failed, or the outcome is uncertain. Retried later with the same idempotency key. */
    FAILED
}
