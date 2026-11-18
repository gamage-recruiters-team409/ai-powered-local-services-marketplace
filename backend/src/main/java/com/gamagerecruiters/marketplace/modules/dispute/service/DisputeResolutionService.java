package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeAppeal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDecisionRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.ResolveDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.mapper.DisputeMapper;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

/**
 * Admin decision, party appeal and the appeal decision by a different admin. Role checks happen in the controllers;
 * this service enforces the per-dispute rules. Every status change is one atomic conditional update
 * ({@link DisputeResolutionStore}); the pre-checks only give the caller a precise error code. Only ids are logged:
 * never notes, grounds or descriptions.
 *
 * <pre>
 * AWAITING_RESPONSE (after responseDueAt) or UNDER_REVIEW --resolve--> RESOLVED (appeal window opens)
 * RESOLVED --appeal (a party, within the window, once)--> APPEALED
 * APPEALED --appeal decision (a different admin)--> RESOLVED_FINAL --> settlement
 * RESOLVED (window passed, no appeal) --DisputeFinalizer--> RESOLVED_FINAL --> settlement
 * </pre>
 */
@Service
public class DisputeResolutionService {

    private static final Logger log = LoggerFactory.getLogger(DisputeResolutionService.class);

    private final DisputeRepository disputeRepository;
    private final DisputeResolutionStore store;
    private final DisputeSettlementService settlementService;
    private final DisputeAdminQueryService adminQueryService;
    private final Clock clock;
    private final Duration appealReviewSla;

    @Autowired
    public DisputeResolutionService(DisputeRepository disputeRepository,
                                    DisputeResolutionStore store,
                                    DisputeSettlementService settlementService,
                                    DisputeAdminQueryService adminQueryService,
                                    @Value("${app.dispute.appeal.review-sla:PT96H}") Duration appealReviewSla) {
        this(disputeRepository, store, settlementService, adminQueryService, Clock.systemUTC(), appealReviewSla);
    }

    DisputeResolutionService(DisputeRepository disputeRepository, DisputeResolutionStore store,
                             DisputeSettlementService settlementService, DisputeAdminQueryService adminQueryService,
                             Clock clock, Duration appealReviewSla) {
        if (appealReviewSla == null || appealReviewSla.isNegative() || appealReviewSla.isZero()) {
            throw new IllegalArgumentException("app.dispute.appeal.review-sla must be greater than zero");
        }
        this.disputeRepository = disputeRepository;
        this.store = store;
        this.settlementService = settlementService;
        this.adminQueryService = adminQueryService;
        this.clock = clock;
        this.appealReviewSla = appealReviewSla;
    }

    // ---------------------------------------------------------------- admin decision

    public AdminDisputeDetailResponse resolve(String adminId, Role adminRole, String disputeId,
                                              ResolveDisputeRequest request) {
        validateDecision(request.action(), request.customerRefundPercent(), request.applyProviderPenalty());
        Dispute current = findVisible(disputeId);
        Instant now = clock.instant();
        assertResolvable(current, now);

        DisputeDecision decision = DisputeDecision.of(request.action(), request.customerRefundPercent(),
                Boolean.TRUE.equals(request.applyProviderPenalty()), adminId, adminRole, now, request.note().strip());
        StatusHistoryEntry entry = new StatusHistoryEntry(current.getStatus(), DisputeStatus.RESOLVED, adminId,
                adminRole, "Decision: " + request.action(), now);

        Dispute resolved = store.resolve(disputeId, current.getStatus(), now, decision,
                        now.plus(DisputeRules.APPEAL_WINDOW), entry)
                // Another admin (or the response) changed it first.
                .orElseThrow(() -> invalidStatus("This dispute can no longer be resolved"));
        log.info("event=DISPUTE_RESOLVED disputeId={} bookingId={} adminId={} action={}",
                resolved.getId(), resolved.getBookingId(), adminId, request.action());
        return adminQueryService.toAdminDetail(resolved);
    }

    private static void assertResolvable(Dispute dispute, Instant now) {
        if (dispute.getStatus() == DisputeStatus.UNDER_REVIEW) {
            return;
        }
        if (dispute.getStatus() == DisputeStatus.AWAITING_RESPONSE) {
            if (dispute.getResponseDueAt() != null && !now.isBefore(dispute.getResponseDueAt())) {
                return;
            }
            throw invalidStatus("The response window is still open; the dispute can be resolved after it closes");
        }
        throw invalidStatus("Only disputes under review (or past their response window) can be resolved");
    }

    // ---------------------------------------------------------------- appeal

    public DisputeDetailResponse appeal(String actorId, Role actorRole, String disputeId, AppealDisputeRequest request) {
        Dispute current = findVisible(disputeId);
        Instant now = clock.instant();
        assertAppealable(current, actorId, now);

        DisputeAppeal appeal = new DisputeAppeal();
        appeal.setAppealedBy(actorId);
        appeal.setAppealedByRole(actorRole);
        appeal.setGrounds(request.grounds().strip());
        appeal.setEvidence(request.evidence());
        appeal.setFiledAt(now);
        appeal.setReviewDueAt(now.plus(appealReviewSla));
        StatusHistoryEntry entry = new StatusHistoryEntry(DisputeStatus.RESOLVED, DisputeStatus.APPEALED, actorId,
                actorRole, "Appeal filed", now);

        Optional<Dispute> appealed = store.fileAppeal(disputeId, actorId, now, appeal, entry);
        if (appealed.isEmpty()) {
            // Lost a race (usually the other party appealed first): report the precise reason.
            assertAppealable(findVisible(disputeId), actorId, now);
            throw invalidStatus("This dispute can no longer be appealed");
        }
        log.info("event=DISPUTE_APPEALED disputeId={} bookingId={} actorId={}",
                disputeId, appealed.get().getBookingId(), actorId);
        return DisputeMapper.toPartyDetail(appealed.get(), actorId, now);
    }

    private static void assertAppealable(Dispute dispute, String actorId, Instant now) {
        if (!dispute.isParty(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY", "You are not a party to this dispute");
        }
        if (dispute.getAppeal() != null) {
            throw new ResourceConflictException("APPEAL_ALREADY_FILED", "An appeal has already been filed for this dispute");
        }
        if (dispute.getStatus() != DisputeStatus.RESOLVED) {
            throw invalidStatus("Only a resolved dispute can be appealed");
        }
        if (dispute.getAppealDeadline() == null || !now.isBefore(dispute.getAppealDeadline())) {
            throw new ResourceConflictException("APPEAL_WINDOW_CLOSED", "The appeal window for this dispute has closed");
        }
    }

    // ---------------------------------------------------------------- appeal decision

    public AdminDisputeDetailResponse decideAppeal(String reviewerId, Role reviewerRole, String disputeId,
                                                   AppealDecisionRequest request) {
        validateAppealDecision(request);
        Dispute current = findVisible(disputeId);
        assertDecidable(current, reviewerId);
        Instant now = clock.instant();
        String note = request.note().strip();

        DisputeDecision binding = request.verdict() == AppealVerdict.UPHOLD
                ? current.getDecision()
                : DisputeDecision.of(request.action(), request.customerRefundPercent(),
                        Boolean.TRUE.equals(request.applyProviderPenalty()), reviewerId, reviewerRole, now, note);
        StatusHistoryEntry entry = new StatusHistoryEntry(DisputeStatus.APPEALED, DisputeStatus.RESOLVED_FINAL,
                reviewerId, reviewerRole, "Appeal " + request.verdict(), now);

        Optional<Dispute> decided = store.decideAppeal(disputeId, current.getVersion(), reviewerId, reviewerRole,
                request.verdict(), note, binding, now, entry);
        if (decided.isEmpty()) {
            assertDecidable(findVisible(disputeId), reviewerId);
            throw new ResourceConflictException("DISPUTE_CONCURRENT_UPDATE",
                    "The dispute was changed by another request. Reload it and try again");
        }
        log.info("event=DISPUTE_APPEAL_DECIDED disputeId={} bookingId={} reviewerId={} verdict={}",
                disputeId, decided.get().getBookingId(), reviewerId, request.verdict());

        // Final and binding. Settlement never changes or rolls back the decision; failures are retried later.
        settlementService.settle(disputeId);
        Dispute latest = disputeRepository.findById(disputeId).orElse(decided.get());
        return adminQueryService.toAdminDetail(latest);
    }

    private static void assertDecidable(Dispute dispute, String reviewerId) {
        if (dispute.getStatus() != DisputeStatus.APPEALED) {
            throw invalidStatus("Only an appealed dispute can receive an appeal decision");
        }
        if (dispute.getDecision() != null && reviewerId.equals(dispute.getDecision().getDecidedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APPEAL_REVIEWER_MUST_DIFFER",
                    "The appeal must be decided by a different admin than the one who made the original decision");
        }
    }

    // ---------------------------------------------------------------- validation and helpers

    /**
     * Decision rules shared by the decision and a CHANGE verdict: the refund percent only for REFUND_PARTIAL and then
     * between 1 and 99; the provider penalty only together with a refund.
     */
    static void validateDecision(ResolutionAction action, Integer customerRefundPercent, Boolean applyProviderPenalty) {
        if (action == ResolutionAction.REFUND_PARTIAL) {
            if (customerRefundPercent == null
                    || customerRefundPercent < DisputeRules.MIN_PARTIAL_REFUND_PERCENT
                    || customerRefundPercent > DisputeRules.MAX_PARTIAL_REFUND_PERCENT) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REFUND_PERCENT",
                        "customerRefundPercent must be between 1 and 99 for REFUND_PARTIAL");
            }
        } else if (customerRefundPercent != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REFUND_PERCENT",
                    "customerRefundPercent is only allowed for REFUND_PARTIAL");
        }
        if (Boolean.TRUE.equals(applyProviderPenalty) && !action.isRefund()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PENALTY_REQUIRES_REFUND",
                    "A provider penalty can only be applied with REFUND_FULL or REFUND_PARTIAL");
        }
    }

    static void validateAppealDecision(AppealDecisionRequest request) {
        if (request.verdict() == AppealVerdict.UPHOLD) {
            if (request.action() != null || request.customerRefundPercent() != null
                    || Boolean.TRUE.equals(request.applyProviderPenalty())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_APPEAL_DECISION",
                        "UPHOLD keeps the original decision; do not send action, customerRefundPercent or a penalty");
            }
            return;
        }
        if (request.action() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_APPEAL_DECISION", "CHANGE requires an action");
        }
        validateDecision(request.action(), request.customerRefundPercent(), request.applyProviderPenalty());
    }

    private Dispute findVisible(String disputeId) {
        return disputeRepository.findById(disputeId)
                .filter(Dispute::isVisible)
                .orElseThrow(() -> new ResourceNotFoundException("DISPUTE_NOT_FOUND", "Dispute not found"));
    }

    private static ResourceConflictException invalidStatus(String message) {
        return new ResourceConflictException("INVALID_DISPUTE_STATUS", message);
    }
}
