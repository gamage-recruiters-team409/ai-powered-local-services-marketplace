package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * An admin decision, embedded in the dispute (the original decision, and the final one when an appeal changes it).
 *
 * <p>Amounts are deliberately NOT stored: no safe booking/payment amount accessor exists yet, so the decision is
 * recorded as an action plus a customer refund percentage. {@code note} is internal and never shown to the parties.</p>
 */
public class DisputeDecision {

    private ResolutionAction action;
    /** Effective customer refund share: 100 for a full refund, 1-99 for a partial refund, 0 for a release. */
    private int customerRefundPercent;
    private boolean providerPenaltyApplied;
    /** {@link DisputeRules#PROVIDER_PENALTY_PERCENT} when a penalty is applied, otherwise 0. */
    private int providerPenaltyPercent;
    private String decidedBy;
    private Role decidedByRole;
    private Instant decidedAt;
    private String note;

    public DisputeDecision() {
    }

    /** Builds a decision from validated input; {@code partialPercent} is used only for REFUND_PARTIAL. */
    public static DisputeDecision of(ResolutionAction action, Integer partialPercent, boolean applyPenalty,
                                     String decidedBy, Role decidedByRole, Instant decidedAt, String note) {
        DisputeDecision decision = new DisputeDecision();
        decision.action = action;
        decision.customerRefundPercent = switch (action) {
            case REFUND_FULL -> 100;
            case REFUND_PARTIAL -> partialPercent;
            case RELEASE_TO_PROVIDER -> 0;
        };
        decision.providerPenaltyApplied = applyPenalty;
        decision.providerPenaltyPercent = applyPenalty ? DisputeRules.PROVIDER_PENALTY_PERCENT : 0;
        decision.decidedBy = decidedBy;
        decision.decidedByRole = decidedByRole;
        decision.decidedAt = decidedAt;
        decision.note = note;
        return decision;
    }

    public ResolutionAction getAction() {
        return action;
    }

    public void setAction(ResolutionAction action) {
        this.action = action;
    }

    public int getCustomerRefundPercent() {
        return customerRefundPercent;
    }

    public void setCustomerRefundPercent(int customerRefundPercent) {
        this.customerRefundPercent = customerRefundPercent;
    }

    public boolean isProviderPenaltyApplied() {
        return providerPenaltyApplied;
    }

    public void setProviderPenaltyApplied(boolean providerPenaltyApplied) {
        this.providerPenaltyApplied = providerPenaltyApplied;
    }

    public int getProviderPenaltyPercent() {
        return providerPenaltyPercent;
    }

    public void setProviderPenaltyPercent(int providerPenaltyPercent) {
        this.providerPenaltyPercent = providerPenaltyPercent;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Role getDecidedByRole() {
        return decidedByRole;
    }

    public void setDecidedByRole(Role decidedByRole) {
        this.decidedByRole = decidedByRole;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
