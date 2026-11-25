package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

/**
 * The dispute content an AI summary may use (WP01 AI-005), in-process only. Structurally excludes internal database
 * identifiers and administrative records: party user IDs, admin IDs, internal notes, appeal review notes, storage URLs,
 * settlement internals, and booking-sync state. Parties are identified only by role (CUSTOMER / PROVIDER).
 * <p>
 * User-entered free-form text (description, response, evidence filenames) undergoes redaction for direct personal
 * identifiers (such as email addresses and telephone numbers) prior to consumption; external AI integrations must
 * process any remaining user-provided context in accordance with approved data-processing and privacy policies.
 *
 * @param raiserDescription  what the raising party wrote (redacted for direct personal identifiers)
 * @param otherPartyResponse the other party's response text, or null if they did not respond (redacted)
 * @param evidence           uploaded evidence files: redacted file name and type (JPG, PNG, WEBP, PDF) only
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
