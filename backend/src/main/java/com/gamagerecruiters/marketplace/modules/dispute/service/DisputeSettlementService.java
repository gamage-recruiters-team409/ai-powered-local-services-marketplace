package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingResolutionPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementRejectedException;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionStore.SettlementRecord;

/**
 * Carries out a final (RESOLVED_FINAL) decision in two steps, with the same idempotency key
 * ({@code dispute-<disputeId>}) for both:
 * <ol>
 *   <li>Finance: {@link DisputeSettlementPort#settle} FIRST.</li>
 *   <li>Booking: {@link BookingResolutionPort#resolveBooking} ONLY after finance returned APPLIED. Finance APPLIED is
 *       saved before the booking step, so a retry repeats only the booking step.</li>
 * </ol>
 *
 * <ul>
 *   <li>REFUND_FULL, REFUND_PARTIAL and the provider penalty: no port is called (TL decision: pending Phase 04
 *       integration). The settlement becomes MANUAL_REVIEW, reason {@value #REFUND_PENDING_INTEGRATION}. The decision
 *       itself stays final.</li>
 *   <li>NOT_APPLIED (integration not connected yet): recorded as NOT_APPLIED with {@code notAppliedReason}; NOT a
 *       failure and not counted; re-checked every {@code app.dispute.settlement.not-applied-recheck}.</li>
 *   <li>{@link SettlementRejectedException} from finance: REJECTED, reason
 *       {@code SETTLEMENT_REJECTED:<reasonCode>}; terminal (never claimed again), the booking port is never called.</li>
 *   <li>Any other exception: FAILED, counted as an attempt, retried with exponential backoff
 *       ({@code backoff.initial} doubled per attempt, at most {@code backoff.max}); when {@code max-attempts} failed
 *       attempts are reached: MANUAL_REVIEW, reason {@value #RETRY_LIMIT_REACHED}.</li>
 *   <li>Only one worker at a time: the dispute's settlement lease is taken atomically first and every write requires
 *       it. Nothing is ever marked APPLIED unless the port returned APPLIED.</li>
 *   <li>The decision and the RESOLVED_FINAL status are NEVER changed, removed or rolled back here.</li>
 * </ul>
 * Only ids, states and error codes are logged.
 */
@Service
public class DisputeSettlementService {

    public static final String REFUND_PENDING_INTEGRATION = "REFUND_PENDING_INTEGRATION";
    public static final String RETRY_LIMIT_REACHED = "RETRY_LIMIT_REACHED";
    public static final String SETTLEMENT_REJECTED_PREFIX = "SETTLEMENT_REJECTED:";
    public static final String FINANCE_NOT_INTEGRATED = "WP07_NOT_INTEGRATED";
    public static final String BOOKING_NOT_INTEGRATED = "WP05_NOT_INTEGRATED";

    /** A rejection reason code is stored and shown to admins: short code characters only. */
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final Logger log = LoggerFactory.getLogger(DisputeSettlementService.class);

    private final DisputeResolutionStore store;
    private final BookingResolutionPort bookingResolutionPort;
    private final DisputeSettlementPort settlementPort;
    private final Clock clock;
    private final Duration lease;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration notAppliedRecheck;

    @Autowired
    public DisputeSettlementService(DisputeResolutionStore store,
                                    BookingResolutionPort bookingResolutionPort,
                                    DisputeSettlementPort settlementPort,
                                    @Value("${app.dispute.settlement.lease:PT5M}") Duration lease,
                                    @Value("${app.dispute.settlement.max-attempts:5}") int maxAttempts,
                                    @Value("${app.dispute.settlement.backoff.initial:PT5M}") Duration initialBackoff,
                                    @Value("${app.dispute.settlement.backoff.max:PT6H}") Duration maxBackoff,
                                    @Value("${app.dispute.settlement.not-applied-recheck:PT1H}")
                                    Duration notAppliedRecheck) {
        this(store, bookingResolutionPort, settlementPort, Clock.systemUTC(), lease, maxAttempts, initialBackoff,
                maxBackoff, notAppliedRecheck);
    }

    DisputeSettlementService(DisputeResolutionStore store, BookingResolutionPort bookingResolutionPort,
                             DisputeSettlementPort settlementPort, Clock clock, Duration lease, int maxAttempts,
                             Duration initialBackoff, Duration maxBackoff, Duration notAppliedRecheck) {
        if (lease.isNegative() || lease.isZero() || maxAttempts < 1 || initialBackoff.isNegative()
                || maxBackoff.compareTo(initialBackoff) < 0 || notAppliedRecheck.isNegative()) {
            throw new IllegalArgumentException("Invalid app.dispute.settlement settings: need lease > 0, "
                    + "max-attempts >= 1, 0 <= backoff.initial <= backoff.max and not-applied-recheck >= 0");
        }
        this.store = store;
        this.bookingResolutionPort = bookingResolutionPort;
        this.settlementPort = settlementPort;
        this.clock = clock;
        this.lease = lease;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.notAppliedRecheck = notAppliedRecheck;
    }

    /**
     * Settles one dispute right after it became final, if no other worker holds it. Never throws: whatever happens,
     * the decision stays and the finalizer retries what is still due.
     */
    public Optional<SettlementState> settle(String disputeId) {
        try {
            Instant now = clock.instant();
            return store.claimSettlement(disputeId, now, lease, now.minus(notAppliedRecheck))
                    .map(claimed -> process(claimed, now));
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SETTLEMENT_DEFERRED disputeId={} cause={}", disputeId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Settles up to {@code batchSize} due disputes, each at most once per run. Returns how many were worked on. */
    public int settleDue(int batchSize) {
        Set<String> done = new HashSet<>();
        for (int i = 0; i < batchSize; i++) {
            Instant now = clock.instant();
            // At most one attempt per dispute per run, even when a failure is due again at once (backoff 0).
            Optional<Dispute> claimed = store.claimSettlement(null, now, lease, now.minus(notAppliedRecheck), done);
            if (claimed.isEmpty()) {
                break;
            }
            done.add(claimed.get().getId());
            process(claimed.get(), now);
        }
        return done.size();
    }

    private SettlementState process(Dispute dispute, Instant now) {
        DisputeSettlement settlement = dispute.getSettlement();
        DisputeDecision decision = dispute.getFinalDecision();
        SettlementState finance = settlement.financeStateOrPending();

        if (decision.getAction().isRefund() || decision.isProviderPenaltyApplied()) {
            // TL decision: refunds and the provider penalty are pending Phase 04 integration. No port is called.
            return record(dispute, new SettlementRecord(SettlementState.MANUAL_REVIEW, finance, SettlementState.PENDING,
                    false, null, null, null, REFUND_PENDING_INTEGRATION), now);
        }
        if (settlement.getAttempts() >= maxAttempts) {
            // max-attempts was lowered after this settlement had already failed that often.
            return record(dispute, new SettlementRecord(SettlementState.MANUAL_REVIEW, finance,
                    bookingStateOf(settlement, finance), false, null, settlement.getLastErrorCode(), null,
                    RETRY_LIMIT_REACHED), now);
        }

        SettlementInstruction instruction = SettlementInstruction.from(dispute.getId(), dispute.getBookingId(),
                dispute.getCustomerId(), dispute.getProviderId(), decision);

        if (finance != SettlementState.APPLIED) {
            SettlementOutcome outcome;
            try {
                outcome = settlementPort.settle(instruction);
            } catch (SettlementRejectedException e) {
                String reason = SETTLEMENT_REJECTED_PREFIX + safeReasonCode(e.getReasonCode());
                log.warn("event=DISPUTE_SETTLEMENT_REJECTED disputeId={} bookingId={} reason={}",
                        dispute.getId(), dispute.getBookingId(), reason);
                return record(dispute, new SettlementRecord(SettlementState.REJECTED, SettlementState.REJECTED,
                        SettlementState.PENDING, false, null, null, null, reason), now);
            } catch (RuntimeException e) {
                return failed(dispute, SettlementState.FAILED, SettlementState.PENDING, "finance", e, now);
            }
            if (outcome != SettlementOutcome.APPLIED) {
                // NOT_APPLIED (stub) or an unknown answer: nothing was applied. Not a failure; re-checked later.
                return record(dispute, new SettlementRecord(SettlementState.NOT_APPLIED, SettlementState.NOT_APPLIED,
                        SettlementState.PENDING, false, null, null, FINANCE_NOT_INTEGRATED, null), now);
            }
            if (!saveFinanceApplied(dispute, now)) {
                // Lease lost or not saved: stop here. The lease expires and the idempotent finance call is repeated.
                return SettlementState.PENDING;
            }
        }

        if (settlement.getBookingState() == SettlementState.APPLIED) {
            // Historical/legacy settlements where booking was already recorded as APPLIED: preserve and do not repeat.
            return record(dispute, new SettlementRecord(SettlementState.APPLIED, SettlementState.APPLIED,
                    SettlementState.APPLIED, false, null, null, null, null), now);
        }

        SettlementOutcome bookingOutcome;
        try {
            bookingOutcome = bookingResolutionPort.resolveBooking(instruction);
        } catch (RuntimeException e) {
            return failed(dispute, SettlementState.APPLIED, SettlementState.FAILED, "booking", e, now);
        }
        if (bookingOutcome != SettlementOutcome.APPLIED) {
            return record(dispute, new SettlementRecord(SettlementState.NOT_APPLIED, SettlementState.APPLIED,
                    SettlementState.NOT_APPLIED, false, null, null, BOOKING_NOT_INTEGRATED, null), now);
        }
        return record(dispute, new SettlementRecord(SettlementState.APPLIED, SettlementState.APPLIED,
                SettlementState.APPLIED, false, null, null, null, null), now);
    }

    /** A counted failure: FAILED with backoff, or MANUAL_REVIEW once the attempt limit is reached. */
    private SettlementState failed(Dispute dispute, SettlementState finance, SettlementState booking, String step,
                                   RuntimeException error, Instant now) {
        int attempt = dispute.getSettlement().getAttempts() + 1;
        String code = errorCode(error);
        log.error("event=DISPUTE_SETTLEMENT_FAILED step={} disputeId={} bookingId={} attempt={} code={}",
                step, dispute.getId(), dispute.getBookingId(), attempt, code);
        if (attempt >= maxAttempts) {
            return record(dispute, new SettlementRecord(SettlementState.MANUAL_REVIEW, finance, booking, true, null,
                    code, null, RETRY_LIMIT_REACHED), now);
        }
        return record(dispute, new SettlementRecord(SettlementState.FAILED, finance, booking, true,
                now.plus(backoff(attempt)), code, null, null), now);
    }

    /** initial * 2^(attempt-1), at most max. */
    Duration backoff(int attempt) {
        Duration delay = initialBackoff;
        for (int i = 1; i < attempt && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private boolean saveFinanceApplied(Dispute dispute, Instant now) {
        try {
            if (store.recordFinanceApplied(dispute.getId(), dispute.getSettlement().getLeaseOwner(), now)) {
                return true;
            }
            log.warn("event=DISPUTE_SETTLEMENT_NOT_RECORDED reason=LEASE_LOST step=finance disputeId={}", dispute.getId());
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SETTLEMENT_NOT_RECORDED step=finance disputeId={} cause={}",
                    dispute.getId(), e.getClass().getSimpleName());
        }
        return false;
    }

    private SettlementState record(Dispute dispute, SettlementRecord result, Instant now) {
        try {
            if (!store.recordSettlement(dispute.getId(), dispute.getSettlement().getLeaseOwner(), result, now)) {
                log.warn("event=DISPUTE_SETTLEMENT_NOT_RECORDED reason=LEASE_LOST disputeId={}", dispute.getId());
            }
        } catch (RuntimeException e) {
            // The lease expires and the attempt is repeated with the same idempotency key.
            log.error("event=DISPUTE_SETTLEMENT_NOT_RECORDED disputeId={} cause={}",
                    dispute.getId(), e.getClass().getSimpleName());
        }
        log.info("event=DISPUTE_SETTLEMENT_ATTEMPTED disputeId={} bookingId={} state={} financeState={} bookingState={}"
                        + " reason={}", dispute.getId(), dispute.getBookingId(), result.state(), result.financeState(),
                result.bookingState(), result.manualReviewReason() != null ? result.manualReviewReason()
                        : result.notAppliedReason());
        return result.state();
    }

    /** The booking step only counts once finance is APPLIED. */
    private static SettlementState bookingStateOf(DisputeSettlement settlement, SettlementState finance) {
        if (finance != SettlementState.APPLIED || settlement.getBookingState() == null) {
            return SettlementState.PENDING;
        }
        return settlement.getBookingState();
    }

    /** The error's code (never its message): an ApiException's code, otherwise the exception type. */
    static String errorCode(RuntimeException error) {
        if (error instanceof ApiException apiException && apiException.getErrorCode() != null) {
            return safeReasonCode(apiException.getErrorCode());
        }
        return error.getClass().getSimpleName();
    }

    static String safeReasonCode(String code) {
        return code != null && REASON_CODE.matcher(code).matches() ? code : "UNSPECIFIED";
    }
}
