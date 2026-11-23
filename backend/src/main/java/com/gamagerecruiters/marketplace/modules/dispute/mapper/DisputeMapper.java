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
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceFile;
import com.gamagerecruiters.marketplace.modules.dispute.domain.PartyResponse;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.AppealReviewView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.DecisionView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.SettlementView;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.AppealView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.OutcomeView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.PartyResponseView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.StatusHistoryView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceView;

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
                toPartyView(dispute.getAppeal()),
                evidenceViews(dispute));
    }

    /** Admin view; names may be null when they cannot be read. {@code actions} are the signed-in admin's actions. */
    public static AdminDisputeDetailResponse toAdminDetail(Dispute dispute, String customerName, String providerName,
                                                           Instant now, AdminDisputeDetailResponse.Actions actions) {
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
                history,
                evidenceViews(dispute),
                actions);
    }

    public static EvidenceView toView(EvidenceFile file) {
        return new EvidenceView(file.getId(), file.getFileName(), file.getType(),
                file.getType() == null ? null : file.getType().mediaType(), file.getSizeBytes(),
                file.getUploadedByRole(), file.getUploadedAt());
    }

    private static List<EvidenceView> evidenceViews(Dispute dispute) {
        return dispute.getEvidenceFiles().stream().map(DisputeMapper::toView).toList();
    }

    /**
     * Content for an AI summary (WP01 AI-005): only the fields of {@link DisputeAiSummaryData}. Parties become
     * CUSTOMER / PROVIDER, history actors lose their ids and reasons, decisions are reduced to their result.
     */
    public static DisputeAiSummaryData toAiSummaryData(Dispute dispute, Instant now) {
        boolean isFinal = dispute.getStatus() == DisputeStatus.RESOLVED_FINAL;
        return new DisputeAiSummaryData(
                dispute.getId(),
                dispute.getStatus(),
                dispute.getCategory(),
                raiserSide(dispute),
                dispute.getDescription(),
                dispute.getResponse() == null ? null : dispute.getResponse().getText(),
                dispute.getEvidenceFiles().stream()
                        .map(f -> new DisputeAiSummaryData.EvidenceItem(f.getFileName(),
                                f.getType() == null ? null : f.getType().name()))
                        .toList(),
                dispute.getStatusHistory().stream()
                        .map(e -> new DisputeAiSummaryData.HistoryItem(e.getToStatus(), e.getTimestamp(),
                                actorKind(e.getActorRole())))
                        .toList(),
                toOutcomeOnly(dispute.getDecision()),
                new DisputeAiSummaryData.AppealResult(appealStatus(dispute, now),
                        isFinal ? toOutcomeOnly(dispute.getFinalDecision()) : null,
                        isFinal ? DisputeStatus.RESOLVED_FINAL : null));
    }

    private static DisputeAiSummaryData.Side raiserSide(Dispute dispute) {
        if (dispute.getRaisedByRole() == Role.PROVIDER) {
            return DisputeAiSummaryData.Side.PROVIDER;
        }
        if (dispute.getRaisedByRole() == Role.CUSTOMER) {
            return DisputeAiSummaryData.Side.CUSTOMER;
        }
        return dispute.getRaisedBy() != null && dispute.getRaisedBy().equals(dispute.getProviderId())
                ? DisputeAiSummaryData.Side.PROVIDER : DisputeAiSummaryData.Side.CUSTOMER;
    }

    private static DisputeAiSummaryData.ActorKind actorKind(Role role) {
        if (role == Role.CUSTOMER) {
            return DisputeAiSummaryData.ActorKind.CUSTOMER;
        }
        if (role == Role.PROVIDER) {
            return DisputeAiSummaryData.ActorKind.PROVIDER;
        }
        return isAdminRole(role) ? DisputeAiSummaryData.ActorKind.ADMIN : DisputeAiSummaryData.ActorKind.SYSTEM;
    }

    private static DisputeAiSummaryData.Outcome toOutcomeOnly(DisputeDecision decision) {
        return decision == null ? null : new DisputeAiSummaryData.Outcome(decision.getAction(),
                decision.getCustomerRefundPercent(), decision.isProviderPenaltyApplied());
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
        boolean isFinal = dispute.getStatus() == DisputeStatus.RESOLVED_FINAL;
        boolean carriedOut = dispute.getSettlement() != null
                && dispute.getSettlement().getState() == SettlementState.APPLIED;
        return new OutcomeView(
                binding.getAction(),
                binding.getCustomerRefundPercent(),
                viewerIsProvider ? binding.isProviderPenaltyApplied() : null,
                binding.getDecidedAt(),
                isFinal,
                isFinal && !carriedOut ? OutcomeView.PAYMENT_IN_PROGRESS : null);
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
                isAdminRole(entry.getActorRole()) ? entry.getActorRole() : null,
                null,
                entry.getTimestamp());
    }

    /** Null-safe: SYSTEM entries (e.g. the finalizer's) have no role, and Set.of(...).contains(null) throws. */
    private static boolean isAdminRole(Role role) {
        return role != null && Roles.ADMIN_ROLES.contains(role);
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
                settlement.financeStateOrPending(),
                settlement.getBookingState(),
                settlement.getAttempts(),
                settlement.getLastAttemptAt(),
                settlement.getNextAttemptAt(),
                settlement.getLastErrorCode(),
                settlement.getNotAppliedReason(),
                settlement.getManualReviewReason());
    }

    /** Admin list row: the summary plus the read-only settlement state and manual review reason. */
    public static AdminDisputeSummaryResponse toAdminSummary(Dispute dispute) {
        DisputeSettlement settlement = dispute.getSettlement();
        return new AdminDisputeSummaryResponse(
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
                dispute.getUpdatedAt(),
                settlement == null ? null : settlement.getState(),
                settlement == null ? null : settlement.getManualReviewReason());
    }
}
