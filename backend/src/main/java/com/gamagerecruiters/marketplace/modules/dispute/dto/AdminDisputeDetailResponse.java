package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.PartyResponseView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.StatusHistoryView;

/**
 * Admin case view (ADMIN, SUPER_ADMIN read-only): everything in the party view plus names, the full decision with
 * the deciding admin and internal note, the appeal review, the settlement state and the full status history.
 * Names are null when they cannot be read. No amounts: no safe amount accessor exists yet.
 */
public record AdminDisputeDetailResponse(
        String id,
        String bookingId,
        String customerId,
        String customerName,
        String providerId,
        String providerName,
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
        DecisionView decision,
        Instant appealDeadline,
        AppealStatus appealStatus,
        AppealReviewView appeal,
        DecisionView finalDecision,
        Instant finalizedAt,
        SettlementView settlement,
        List<StatusHistoryView> statusHistory
) {

    public record DecisionView(
            ResolutionAction action,
            int customerRefundPercent,
            boolean providerPenaltyApplied,
            int providerPenaltyPercent,
            String decidedBy,
            Role decidedByRole,
            Instant decidedAt,
            String note
    ) {
    }

    public record AppealReviewView(
            String appealedBy,
            Role appealedByRole,
            String grounds,
            List<String> evidence,
            Instant filedAt,
            Instant reviewDueAt,
            AppealVerdict verdict,
            String reviewedBy,
            Instant reviewedAt,
            String reviewNote
    ) {
    }

    /** NOT_APPLIED means nothing was carried out yet (no real integration). */
    public record SettlementView(
            SettlementState state,
            SettlementState bookingState,
            SettlementState payoutState,
            int attempts,
            Instant lastAttemptAt
    ) {
    }
}
