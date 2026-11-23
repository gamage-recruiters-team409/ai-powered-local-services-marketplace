package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;

/**
 * How far the final decision has been carried out, embedded in the dispute. Two steps, tracked separately and run in
 * this order: {@code financeState} (DisputeSettlementPort, WP07) first, then {@code bookingState}
 * (BookingResolutionPort, WP05) only after finance is APPLIED. A retry never repeats an APPLIED step.
 *
 * <ul>
 *   <li>{@code state}: the overall state (see {@link SettlementState}).</li>
 *   <li>{@code attempts}: FAILED attempts only (NOT_APPLIED is not a failure); {@code nextAttemptAt}: when a FAILED
 *       settlement may be retried (backoff).</li>
 *   <li>{@code lastErrorCode}: error code or exception type of the last failure (never a message).</li>
 *   <li>{@code notAppliedReason}: why nothing was applied yet (e.g. WP07_NOT_INTEGRATED).</li>
 *   <li>{@code manualReviewReason}: why an admin must look at it (REFUND_PENDING_INTEGRATION,
 *       RETRY_LIMIT_REACHED, SETTLEMENT_REJECTED:&lt;reasonCode&gt;).</li>
 *   <li>{@code leaseUntil}/{@code leaseOwner}: only one worker calls the ports at a time.</li>
 * </ul>
 * Internal: admins see the states and reasons, parties see none of it.
 */
public class DisputeSettlement {

    private SettlementState state;
    private SettlementState financeState;
    private SettlementState bookingState;
    private int attempts;
    private Instant lastAttemptAt;
    private Instant nextAttemptAt;
    private String lastErrorCode;
    private String notAppliedReason;
    private String manualReviewReason;
    private Instant leaseUntil;
    private String leaseOwner;

    /** A new, not yet attempted settlement. */
    public static DisputeSettlement pending() {
        DisputeSettlement settlement = new DisputeSettlement();
        settlement.state = SettlementState.PENDING;
        settlement.financeState = SettlementState.PENDING;
        settlement.bookingState = SettlementState.PENDING;
        return settlement;
    }

    /** Finance step state; a record written before the field existed counts as PENDING (it is re-checked). */
    public SettlementState financeStateOrPending() {
        return financeState == null ? SettlementState.PENDING : financeState;
    }

    public SettlementState getState() {
        return state;
    }

    public void setState(SettlementState state) {
        this.state = state;
    }

    public SettlementState getFinanceState() {
        return financeState;
    }

    public void setFinanceState(SettlementState financeState) {
        this.financeState = financeState;
    }

    public SettlementState getBookingState() {
        return bookingState;
    }

    public void setBookingState(SettlementState bookingState) {
        this.bookingState = bookingState;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getNotAppliedReason() {
        return notAppliedReason;
    }

    public void setNotAppliedReason(String notAppliedReason) {
        this.notAppliedReason = notAppliedReason;
    }

    public String getManualReviewReason() {
        return manualReviewReason;
    }

    public void setManualReviewReason(String manualReviewReason) {
        this.manualReviewReason = manualReviewReason;
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
