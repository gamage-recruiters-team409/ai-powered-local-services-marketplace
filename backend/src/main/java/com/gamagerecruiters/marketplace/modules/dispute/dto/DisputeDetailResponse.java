package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

/**
 * Dispute view for the parties (customer and provider). It never contains an admin's identity or internal notes:
 * admin and system entries of the status history are shown without actor id and reason.
 */
public record DisputeDetailResponse(
        String id,
        String bookingId,
        String customerId,
        String providerId,
        String raisedBy,
        Role raisedByRole,
        DisputeCategory category,
        String description,
        List<String> evidence,
        DisputeStatus status,
        PartyResponseView response,
        Instant responseDueAt,
        Instant createdAt,
        Instant updatedAt,
        List<StatusHistoryView> statusHistory,
        /* The binding decision so far (the final one once final), or null before any decision. */
        OutcomeView outcome,
        /* True only for a party of the dispute while it is RESOLVED, within the window, and not appealed yet. */
        boolean appealEligible,
        Instant appealDeadline,
        AppealStatus appealStatus,
        AppealView appeal
) {

    /** A dispute without any decision yet (raise and response results). */
    public DisputeDetailResponse(String id, String bookingId, String customerId, String providerId, String raisedBy,
                                 Role raisedByRole, DisputeCategory category, String description, List<String> evidence,
                                 DisputeStatus status, PartyResponseView response, Instant responseDueAt,
                                 Instant createdAt, Instant updatedAt, List<StatusHistoryView> statusHistory) {
        this(id, bookingId, customerId, providerId, raisedBy, raisedByRole, category, description, evidence, status,
                response, responseDueAt, createdAt, updatedAt, statusHistory, null, false, null,
                AppealStatus.NOT_AVAILABLE, null);
    }

    public record PartyResponseView(String responderId, String text, List<String> evidence, Instant respondedAt) {
    }

    public record StatusHistoryView(
            DisputeStatus fromStatus,
            DisputeStatus toStatus,
            String actorId,
            Role actorRole,
            String reason,
            Instant timestamp
    ) {
    }

    /**
     * The decision as the parties see it. {@code providerPenaltyApplied} is shown to the provider only (null for the
     * customer). No admin identity, no note. {@code paymentNote} is a neutral text while a final decision is still
     * being carried out ({@link #PAYMENT_IN_PROGRESS}), else null: parties never see settlement states or reasons.
     */
    public record OutcomeView(
            ResolutionAction action,
            int customerRefundPercent,
            Boolean providerPenaltyApplied,
            Instant decidedAt,
            boolean finalDecision,
            String paymentNote
    ) {

        public static final String PAYMENT_IN_PROGRESS = "Payment handling for this dispute is being processed.";
    }

    /** The appeal as the parties see it: no reviewer identity, no review note. */
    public record AppealView(
            Role appealedByRole,
            String grounds,
            List<String> evidence,
            Instant filedAt,
            Instant reviewDueAt,
            AppealVerdict verdict,
            Instant reviewedAt
    ) {
    }
}
