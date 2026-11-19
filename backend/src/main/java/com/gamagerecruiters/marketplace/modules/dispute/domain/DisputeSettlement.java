package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;

/**
 * How far the final decision has been carried out, embedded in the dispute. One state per port (booking status,
 * payout/refund/penalty) plus the overall {@code state}; a retry only calls ports that are PENDING or FAILED.
 * {@code leaseUntil}/{@code leaseOwner} make sure only one worker calls the ports at a time. Internal: admins see the
 * states, parties see nothing of it.
 */
public class DisputeSettlement {

    private SettlementState state;
    private SettlementState bookingState;
    private SettlementState payoutState;
    private int attempts;
    private Instant lastAttemptAt;
    private Instant leaseUntil;
    private String leaseOwner;

    /** A new, not yet attempted settlement. */
    public static DisputeSettlement pending() {
        DisputeSettlement settlement = new DisputeSettlement();
        settlement.state = SettlementState.PENDING;
        settlement.bookingState = SettlementState.PENDING;
        settlement.payoutState = SettlementState.PENDING;
        return settlement;
    }

    /**
     * Overall state from the two port states: APPLIED only when both are APPLIED; FAILED when any failed (it will be
     * retried); NOT_APPLIED when nothing failed but something was not applied (no real integration); else PENDING.
     */
    public static SettlementState overall(SettlementState booking, SettlementState payout) {
        if (booking == SettlementState.FAILED || payout == SettlementState.FAILED) {
            return SettlementState.FAILED;
        }
        if (booking == SettlementState.APPLIED && payout == SettlementState.APPLIED) {
            return SettlementState.APPLIED;
        }
        if (booking == SettlementState.NOT_APPLIED || payout == SettlementState.NOT_APPLIED) {
            return SettlementState.NOT_APPLIED;
        }
        return SettlementState.PENDING;
    }

    public SettlementState getState() {
        return state;
    }

    public void setState(SettlementState state) {
        this.state = state;
    }

    public SettlementState getBookingState() {
        return bookingState;
    }

    public void setBookingState(SettlementState bookingState) {
        this.bookingState = bookingState;
    }

    public SettlementState getPayoutState() {
        return payoutState;
    }

    public void setPayoutState(SettlementState payoutState) {
        this.payoutState = payoutState;
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
