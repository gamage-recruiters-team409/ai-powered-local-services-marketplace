package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeAppeal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;

/**
 * Atomic, conditional status changes of the decision/appeal/finalization lifecycle. Each change is ONE
 * findAndModify whose filter contains every rule that must still hold (expected status, appeal window, "no appeal
 * yet", "reviewer is not the deciding admin", ...), so of two concurrent requests exactly one can succeed; the other
 * gets empty and the caller answers 409/403. Every change appends its status-history entry in the same update and
 * increments the optimistic-locking version.
 *
 * <p>Settlement calls are serialized with an owner-tagged lease (same pattern as the booking-sync store).</p>
 */
@Component
public class DisputeResolutionStore {

    static final String F_ID = "_id";
    static final String F_STATUS = "status";
    static final String F_VERSION = "version";
    static final String F_SYNC = "bookingSyncState";
    static final String F_HISTORY = "statusHistory";
    static final String F_UPDATED = "updatedAt";
    static final String F_DECISION = "decision";
    static final String F_DECIDED_BY = "decision.decidedBy";
    static final String F_APPEAL = "appeal";
    static final String F_APPEAL_DEADLINE = "appealDeadline";
    static final String F_FINAL_DECISION = "finalDecision";
    static final String F_FINALIZED_AT = "finalizedAt";
    static final String F_ACTIVE_KEY = "activeBookingKey";
    static final String F_SETTLEMENT = "settlement";
    static final String F_S_STATE = "settlement.state";
    static final String F_S_FINANCE = "settlement.financeState";
    static final String F_S_BOOKING = "settlement.bookingState";
    /** Written before the finance/booking split; removed on the next recorded attempt. */
    static final String F_S_LEGACY_PAYOUT = "settlement.payoutState";
    static final String F_S_ATTEMPTS = "settlement.attempts";
    static final String F_S_LAST = "settlement.lastAttemptAt";
    static final String F_S_NEXT = "settlement.nextAttemptAt";
    static final String F_S_ERROR = "settlement.lastErrorCode";
    static final String F_S_NOT_APPLIED_REASON = "settlement.notAppliedReason";
    static final String F_S_REVIEW_REASON = "settlement.manualReviewReason";
    static final String F_S_LEASE = "settlement.leaseUntil";
    static final String F_S_OWNER = "settlement.leaseOwner";

    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    private final MongoTemplate mongoTemplate;

    public DisputeResolutionStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private static Criteria visibleById(String disputeId) {
        return Criteria.where(F_ID).is(disputeId).and(F_SYNC).ne(BookingSyncState.PENDING);
    }

    private static Update change(DisputeStatus to, StatusHistoryEntry entry, Instant now) {
        return new Update()
                .set(F_STATUS, to)
                .set(F_UPDATED, now)
                .push(F_HISTORY, entry)
                .inc(F_VERSION, 1);
    }

    /**
     * {@code expected} -> RESOLVED with the decision and the appeal deadline. From AWAITING_RESPONSE only once the
     * response window has passed. Empty if the dispute is no longer in that state.
     */
    public Optional<Dispute> resolve(String disputeId, DisputeStatus expected, Instant now, DisputeDecision decision,
                                     Instant appealDeadline, StatusHistoryEntry entry) {
        Criteria criteria = visibleById(disputeId).and(F_STATUS).is(expected);
        if (expected == DisputeStatus.AWAITING_RESPONSE) {
            criteria = criteria.and("responseDueAt").lte(now);
        }
        Update update = change(DisputeStatus.RESOLVED, entry, now)
                .set(F_DECISION, decision)
                .set(F_APPEAL_DEADLINE, appealDeadline);
        return Optional.ofNullable(mongoTemplate.findAndModify(Query.query(criteria), update, RETURN_NEW, Dispute.class));
    }

    /**
     * RESOLVED -> APPEALED, only for a party of the dispute, within the window, and only if no appeal exists yet.
     * Empty if any of these no longer holds.
     */
    public Optional<Dispute> fileAppeal(String disputeId, String actorId, Instant now, DisputeAppeal appeal,
                                        StatusHistoryEntry entry) {
        Criteria criteria = visibleById(disputeId)
                .and(F_STATUS).is(DisputeStatus.RESOLVED)
                .and(F_APPEAL).is(null)
                .and(F_APPEAL_DEADLINE).gt(now)
                .orOperator(Criteria.where("customerId").is(actorId), Criteria.where("providerId").is(actorId));
        Update update = change(DisputeStatus.APPEALED, entry, now).set(F_APPEAL, appeal);
        return Optional.ofNullable(mongoTemplate.findAndModify(Query.query(criteria), update, RETURN_NEW, Dispute.class));
    }

    /**
     * APPEALED -> RESOLVED_FINAL with the reviewer's verdict and the binding decision. The filter requires that the
     * reviewer is NOT the admin who made the original decision, and the version that was read (so the binding
     * decision is based on the current document). Settlement starts PENDING. Empty if any rule no longer holds.
     */
    public Optional<Dispute> decideAppeal(String disputeId, long expectedVersion, String reviewerId, Role reviewerRole,
                                          AppealVerdict verdict, String reviewNote, DisputeDecision finalDecision,
                                          Instant now, StatusHistoryEntry entry) {
        Criteria criteria = visibleById(disputeId)
                .and(F_STATUS).is(DisputeStatus.APPEALED)
                .and(F_VERSION).is(expectedVersion)
                .and(F_DECIDED_BY).ne(reviewerId);
        Update update = finalUpdate(finalDecision, entry, now)
                .set("appeal.verdict", verdict)
                .set("appeal.reviewedBy", reviewerId)
                .set("appeal.reviewedByRole", reviewerRole)
                .set("appeal.reviewedAt", now)
                .set("appeal.reviewNote", reviewNote);
        return Optional.ofNullable(mongoTemplate.findAndModify(Query.query(criteria), update, RETURN_NEW, Dispute.class));
    }

    /** RESOLVED disputes whose appeal window has passed without an appeal, oldest deadline first. */
    public List<Dispute> findExpiredResolved(Instant now, int limit) {
        Query query = Query.query(Criteria.where(F_STATUS).is(DisputeStatus.RESOLVED)
                        .and(F_SYNC).ne(BookingSyncState.PENDING)
                        .and(F_APPEAL).is(null)
                        .and(F_APPEAL_DEADLINE).lte(now))
                .with(Sort.by(Sort.Direction.ASC, F_APPEAL_DEADLINE))
                .limit(limit);
        return mongoTemplate.find(query, Dispute.class);
    }

    /**
     * RESOLVED -> RESOLVED_FINAL for an expired, unappealed dispute; the original decision becomes binding. The
     * filter repeats every rule plus the version read, so of several finalizer instances only one can succeed.
     */
    public Optional<Dispute> finalizeExpired(Dispute candidate, Instant now, StatusHistoryEntry entry) {
        Criteria criteria = visibleById(candidate.getId())
                .and(F_STATUS).is(DisputeStatus.RESOLVED)
                .and(F_APPEAL).is(null)
                .and(F_APPEAL_DEADLINE).lte(now)
                .and(F_VERSION).is(candidate.getVersion());
        Update update = finalUpdate(candidate.getDecision(), entry, now);
        return Optional.ofNullable(mongoTemplate.findAndModify(Query.query(criteria), update, RETURN_NEW, Dispute.class));
    }

    private static Update finalUpdate(DisputeDecision finalDecision, StatusHistoryEntry entry, Instant now) {
        return change(DisputeStatus.RESOLVED_FINAL, entry, now)
                .set(F_FINAL_DECISION, finalDecision)
                .set(F_FINALIZED_AT, now)
                // activeBookingKey is KEPT: the booking is still DISPUTED until the booking-resolution port confirms
                // the change, and a booking that is still DISPUTED may pass the Booking module's eligibility check.
                // The unique key keeps blocking a second dispute for it (see recordSettlement).
                .set(F_SETTLEMENT, DisputeSettlement.pending());
    }

    /**
     * Takes the settlement lease of one RESOLVED_FINAL dispute that is due, with a free lease:
     * <ul>
     *   <li>PENDING (never attempted);</li>
     *   <li>FAILED whose backoff has passed ({@code nextAttemptAt <= now}; a record without it is due);</li>
     *   <li>NOT_APPLIED (integration not connected yet) last checked at or before {@code recheckNotAppliedBefore}.</li>
     * </ul>
     * APPLIED, REJECTED and MANUAL_REVIEW are never claimed. {@code disputeId} null means any due dispute. Every branch
     * filters on {@code settlement.state} first, so the partial index {@code settlement.state_1_settlement.lastAttemptAt_1}
     * serves the query. The returned record carries the new lease token.
     */
    public Optional<Dispute> claimSettlement(String disputeId, Instant now, Duration lease,
                                             Instant recheckNotAppliedBefore) {
        return claimSettlement(disputeId, now, lease, recheckNotAppliedBefore, List.of());
    }

    /** As above, skipping {@code excludeIds} (disputes this run already worked on: at most one attempt per run). */
    public Optional<Dispute> claimSettlement(String disputeId, Instant now, Duration lease,
                                             Instant recheckNotAppliedBefore, Collection<String> excludeIds) {
        Criteria due = new Criteria().orOperator(
                Criteria.where(F_S_STATE).is(SettlementState.PENDING),
                Criteria.where(F_S_STATE).is(SettlementState.FAILED).orOperator(
                        Criteria.where(F_S_NEXT).lte(now),
                        Criteria.where(F_S_NEXT).exists(false),
                        Criteria.where(F_S_NEXT).is(null)),
                Criteria.where(F_S_STATE).is(SettlementState.NOT_APPLIED).orOperator(
                        Criteria.where(F_S_LAST).lte(recheckNotAppliedBefore),
                        Criteria.where(F_S_LAST).exists(false),
                        Criteria.where(F_S_LAST).is(null)));
        Criteria leaseFree = new Criteria().orOperator(
                Criteria.where(F_S_LEASE).exists(false),
                Criteria.where(F_S_LEASE).is(null),
                Criteria.where(F_S_LEASE).lt(now));
        Criteria criteria = Criteria.where(F_STATUS).is(DisputeStatus.RESOLVED_FINAL);
        if (disputeId != null) {
            criteria = criteria.and(F_ID).is(disputeId);
        } else if (!excludeIds.isEmpty()) {
            criteria = criteria.and(F_ID).nin(excludeIds);
        }
        criteria = criteria.andOperator(due, leaseFree);
        Update update = new Update()
                .set(F_S_LEASE, now.plus(lease))
                .set(F_S_OWNER, UUID.randomUUID().toString())
                .inc(F_VERSION, 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(Query.query(criteria), update, RETURN_NEW, Dispute.class));
    }

    /**
     * Saves that the finance step is APPLIED right away (lease kept), before the booking step runs, so a later retry
     * repeats only the booking step and never calls the settlement port again. True if saved (lease still held).
     */
    public boolean recordFinanceApplied(String disputeId, String leaseOwner, Instant now) {
        Query query = Query.query(Criteria.where(F_ID).is(disputeId)
                .and(F_STATUS).is(DisputeStatus.RESOLVED_FINAL)
                .and(F_S_OWNER).is(leaseOwner));
        Update update = new Update()
                .set(F_S_FINANCE, SettlementState.APPLIED)
                .set(F_S_LAST, now)
                .unset(F_S_NOT_APPLIED_REASON)
                .inc(F_VERSION, 1);
        return mongoTemplate.updateFirst(query, update, Dispute.class).getModifiedCount() == 1;
    }

    /** The result of one settlement attempt (see {@link #recordSettlement}). Null strings/times are removed. */
    public record SettlementRecord(SettlementState state, SettlementState financeState, SettlementState bookingState,
                                   boolean countsAsAttempt, Instant nextAttemptAt, String lastErrorCode,
                                   String notAppliedReason, String manualReviewReason) {
    }

    /**
     * Records one settlement attempt and releases the lease, only while the caller still holds it. Never touches the
     * status or the decision. Only when the booking step is confirmed APPLIED (the booking has left DISPUTED) is the
     * booking's active-dispute key released; every other state keeps it, so no second dispute can be created for a
     * booking that is still DISPUTED. True if recorded.
     */
    public boolean recordSettlement(String disputeId, String leaseOwner, SettlementRecord result, Instant now) {
        Query query = Query.query(Criteria.where(F_ID).is(disputeId)
                .and(F_STATUS).is(DisputeStatus.RESOLVED_FINAL)
                .and(F_S_OWNER).is(leaseOwner));
        Update update = new Update()
                .set(F_S_STATE, result.state())
                .set(F_S_FINANCE, result.financeState())
                .set(F_S_BOOKING, result.bookingState())
                .set(F_S_LAST, now)
                .unset(F_S_LEGACY_PAYOUT)
                .unset(F_S_LEASE)
                .unset(F_S_OWNER)
                .inc(F_VERSION, 1);
        if (result.countsAsAttempt()) {
            update.inc(F_S_ATTEMPTS, 1);
        }
        setOrUnset(update, F_S_NEXT, result.nextAttemptAt());
        setOrUnset(update, F_S_ERROR, result.lastErrorCode());
        setOrUnset(update, F_S_NOT_APPLIED_REASON, result.notAppliedReason());
        setOrUnset(update, F_S_REVIEW_REASON, result.manualReviewReason());
        if (result.bookingState() == SettlementState.APPLIED) {
            update.unset(F_ACTIVE_KEY);
        }
        return mongoTemplate.updateFirst(query, update, Dispute.class).getModifiedCount() == 1;
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }
}
