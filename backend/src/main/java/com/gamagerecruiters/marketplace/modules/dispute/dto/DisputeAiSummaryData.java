package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

/**
 * The dispute content an AI summary may use (WP01 AI-005), in-process only. Deliberately contains NO personal or
 * internal data: no names, emails, phones, user ids of the parties, admin ids, admin notes, appeal review notes,
 * settlement internals or booking-sync state. Parties are only CUSTOMER / PROVIDER.
 *
 * @param raiserDescription  what the raising party wrote
 * @param otherPartyResponse the other party's response text, or null if they did not respond
 * @param evidence           evidence files (name and type only); empty until evidence uploads exist
 * @param decision           the admin's original decision, or null before any decision
 * @param appeal             the appeal state and, once final, the binding result
 */
public record DisputeAiSummaryData(
        String id,
        DisputeStatus status,
        DisputeCategory category,
        Side raisedBy,
        String raiserDescription,
        String otherPartyResponse,
        List<EvidenceItem> evidence,
        List<HistoryItem> statusHistory,
        Outcome decision,
        AppealResult appeal
) {

    /** Which party of the booking. */
    public enum Side {
        CUSTOMER,
        PROVIDER
    }

    /** Who made a status change, without identity: CUSTOMER, PROVIDER, ADMIN (any admin role) or SYSTEM. */
    public enum ActorKind {
        CUSTOMER,
        PROVIDER,
        ADMIN,
        SYSTEM
    }

    public record EvidenceItem(String fileName, String type) {
    }

    public record HistoryItem(DisputeStatus status, Instant at, ActorKind role) {
    }

    /** A decision as its result only: no admin, no note, no amounts. */
    public record Outcome(ResolutionAction action, int customerRefundPercent, boolean providerPenaltyApplied) {
    }

    /**
     * @param outcome       NOT_AVAILABLE, OPEN, FILED, UPHELD, CHANGED or CLOSED
     * @param finalDecision the binding decision once the dispute is final (null before)
     * @param finalStatus   RESOLVED_FINAL once the decision is binding, else null
     */
    public record AppealResult(AppealStatus outcome, Outcome finalDecision, DisputeStatus finalStatus) {
    }
}
