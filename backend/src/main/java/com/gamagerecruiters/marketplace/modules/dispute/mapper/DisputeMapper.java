package com.gamagerecruiters.marketplace.modules.dispute.mapper;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeAppeal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.PartyResponse;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.AppealReviewView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.DecisionView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.SettlementView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.AppealView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.OutcomeView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.PartyResponseView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.StatusHistoryView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;

public final class DisputeMapper {

    private DisputeMapper() {
    }

    /** Party view without a known viewer (raise and response results): no penalty detail, not appeal-eligible. */
    public static DisputeDetailResponse toDetail(Dispute dispute) {
        return toPartyDetail(dispute, null, null);
    }

    /**
     * Party view for {@code viewerId} at {@code now}. Never contains an admin's identity or note: the status history
     * hides actor and reason of admin/system entries, and the decision and appeal are reduced to their outcome.
     */
    public static DisputeDetailResponse toPartyDetail(Dispute dispute, String viewerId, Instant now) {
        List<StatusHistoryView> history = dispute.getStatusHistory().stream()
                .map(DisputeMapper::toPartyView)
                .toList();
        boolean appealEligible = viewerId != null && now != null && dispute.isParty(viewerId)
                && dispute.getStatus() == DisputeStatus.RESOLVED
                && dispute.getAppeal() == null
                && dispute.getAppealDeadline() != null && now.isBefore(dispute.getAppealDeadline());
        return new DisputeDetailResponse(
                dispute.getId(),
                dispute.getBookingId(),
                dispute.getCustomerId(),
                dispute.getProviderId(),
                dispute.getRaisedBy(),
                dispute.getRaisedByRole(),
                dispute.getCategory(),
                dispute.getDescription(),
                List.copyOf(dispute.getEvidence()),
                dispute.getStatus(),
                toView(dispute.getResponse()),
                dispute.getResponseDueAt(),
                dispute.getCreatedAt(),
                dispute.getUpdatedAt(),
                history,
                toOutcome(dispute, viewerId),
                appealEligible,
                dispute.getAppealDeadline(),
                appealStatus(dispute, now),
                toPartyView(dispute.getAppeal()));
    }

    /** Admin view; names may be null when they cannot be read. */
    public static AdminDisputeDetailResponse toAdminDetail(Dispute dispute, String customerName, String providerName,
                                                           Instant now) {
        List<StatusHistoryView> history = dispute.getStatusHistory().stream()
                .map(DisputeMapper::toView)
                .toList();
        return new AdminDisputeDetailResponse(
                dispute.getId(),
                dispute.getBookingId(),
                dispute.getCustomerId(),
                customerName,
                dispute.getProviderId(),
                providerName,
                dispute.getRaisedBy(),
                dispute.getRaisedByRole(),
                dispute.getCategory(),
                dispute.getDescription(),
                List.copyOf(dispute.getEvidence()),
                dispute.getStatus(),
                toView(dispute.getResponse()),
                dispute.getResponseDueAt(),
                dispute.getCreatedAt(),
                dispute.getUpdatedAt(),
                toView(dispute.getDecision()),
                dispute.getAppealDeadline(),
                appealStatus(dispute, now),
                toAdminView(dispute.getAppeal()),
                toView(dispute.getFinalDecision()),
                dispute.getFinalizedAt(),
                toView(dispute.getSettlement()),
                history);
    }

    public static DisputeSummaryResponse toSummary(Dispute dispute) {
        return new DisputeSummaryResponse(
                dispute.getId(),
                dispute.getBookingId(),
                dispute.getCustomerId(),
                dispute.getProviderId(),
                dispute.getRaisedByRole(),
                dispute.getCategory(),
                dispute.getStatus(),
                dispute.getResponseDueAt(),
                dispute.getAppealDeadline(),
                dispute.getCreatedAt(),
                dispute.getUpdatedAt());
    }

    /** Appeal state at {@code now} (when {@code now} is null, an undecided RESOLVED dispute counts as OPEN). */
    public static AppealStatus appealStatus(Dispute dispute, Instant now) {
        if (dispute.getDecision() == null) {
            return AppealStatus.NOT_AVAILABLE;
        }
        DisputeAppeal appeal = dispute.getAppeal();
        if (appeal != null) {
            if (appeal.getVerdict() == null) {
                return AppealStatus.FILED;
            }
            return appeal.getVerdict() == AppealVerdict.UPHOLD ? AppealStatus.UPHELD : AppealStatus.CHANGED;
        }
        boolean windowOpen = dispute.getStatus() == DisputeStatus.RESOLVED
                && (now == null || (dispute.getAppealDeadline() != null && now.isBefore(dispute.getAppealDeadline())));
        return windowOpen ? AppealStatus.OPEN : AppealStatus.CLOSED;
    }

    private static OutcomeView toOutcome(Dispute dispute, String viewerId) {
        DisputeDecision binding = dispute.getFinalDecision() != null ? dispute.getFinalDecision() : dispute.getDecision();
        if (binding == null) {
            return null;
        }
        boolean viewerIsProvider = viewerId != null && viewerId.equals(dispute.getProviderId());
        return new OutcomeView(
                binding.getAction(),
                binding.getCustomerRefundPercent(),
                viewerIsProvider ? binding.isProviderPenaltyApplied() : null,
                binding.getDecidedAt(),
                dispute.getStatus() == DisputeStatus.RESOLVED_FINAL);
    }

    private static PartyResponseView toView(PartyResponse response) {
        if (response == null) {
            return null;
        }
        return new PartyResponseView(
                response.getResponderId(),
                response.getText(),
                List.copyOf(response.getEvidence()),
                response.getRespondedAt());
    }

    private static StatusHistoryView toView(StatusHistoryEntry entry) {
        return new StatusHistoryView(
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getActorId(),
                entry.getActorRole(),
                entry.getReason(),
                entry.getTimestamp());
    }

    /** Admin and system entries lose their actor id and reason; party entries are shown as they are. */
    private static StatusHistoryView toPartyView(StatusHistoryEntry entry) {
        if (isPartyRole(entry.getActorRole())) {
            return toView(entry);
        }
        return new StatusHistoryView(
                entry.getFromStatus(),
                entry.getToStatus(),
                null,
                Roles.ADMIN_ROLES.contains(entry.getActorRole()) ? entry.getActorRole() : null,
                null,
                entry.getTimestamp());
    }

    private static boolean isPartyRole(Role role) {
        return role == Role.CUSTOMER || role == Role.PROVIDER;
    }

    private static AppealView toPartyView(DisputeAppeal appeal) {
        if (appeal == null) {
            return null;
        }
        return new AppealView(
                appeal.getAppealedByRole(),
                appeal.getGrounds(),
                List.copyOf(appeal.getEvidence()),
                appeal.getFiledAt(),
                appeal.getReviewDueAt(),
                appeal.getVerdict(),
                appeal.getReviewedAt());
    }

    private static AppealReviewView toAdminView(DisputeAppeal appeal) {
        if (appeal == null) {
            return null;
        }
        return new AppealReviewView(
                appeal.getAppealedBy(),
                appeal.getAppealedByRole(),
                appeal.getGrounds(),
                List.copyOf(appeal.getEvidence()),
                appeal.getFiledAt(),
                appeal.getReviewDueAt(),
                appeal.getVerdict(),
                appeal.getReviewedBy(),
                appeal.getReviewedAt(),
                appeal.getReviewNote());
    }

    private static DecisionView toView(DisputeDecision decision) {
        if (decision == null) {
            return null;
        }
        return new DecisionView(
                decision.getAction(),
                decision.getCustomerRefundPercent(),
                decision.isProviderPenaltyApplied(),
                decision.getProviderPenaltyPercent(),
                decision.getDecidedBy(),
                decision.getDecidedByRole(),
                decision.getDecidedAt(),
                decision.getNote());
    }

    private static SettlementView toView(DisputeSettlement settlement) {
        if (settlement == null) {
            return null;
        }
        return new SettlementView(
                settlement.getState(),
                settlement.getBookingState(),
                settlement.getPayoutState(),
                settlement.getAttempts(),
                settlement.getLastAttemptAt());
    }
}
