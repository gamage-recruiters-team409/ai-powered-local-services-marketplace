package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingResolutionPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;

/**
 * Carries out a final (RESOLVED_FINAL) decision through {@link BookingResolutionPort} and
 * {@link DisputeSettlementPort}.
 *
 * <ul>
 *   <li>Only one worker at a time: the dispute's settlement lease is taken atomically first.</li>
 *   <li>Each port is called only while its own state is PENDING or FAILED, with the same idempotency key every time,
 *       so an applied part is never applied again.</li>
 *   <li>APPLIED only when a port confirms it; a stub answers NOT_APPLIED (nothing applied, not retried
 *       automatically); a failure or uncertain outcome (exception) is FAILED and retried after
 *       {@code app.dispute.settlement.retry-delay}, at most {@code max-attempts} times.</li>
 *   <li>The decision and the RESOLVED_FINAL status are NEVER changed, removed or rolled back here.</li>
 * </ul>
 * Only ids are logged.
 */
@Service
public class DisputeSettlementService {

    private static final Logger log = LoggerFactory.getLogger(DisputeSettlementService.class);

    private final DisputeResolutionStore store;
    private final BookingResolutionPort bookingResolutionPort;
    private final DisputeSettlementPort settlementPort;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryDelay;
    private final int maxAttempts;

    @Autowired
    public DisputeSettlementService(DisputeResolutionStore store,
                                    BookingResolutionPort bookingResolutionPort,
                                    DisputeSettlementPort settlementPort,
                                    @Value("${app.dispute.settlement.lease:PT5M}") Duration lease,
                                    @Value("${app.dispute.settlement.retry-delay:PT15M}") Duration retryDelay,
                                    @Value("${app.dispute.settlement.max-attempts:10}") int maxAttempts) {
        this(store, bookingResolutionPort, settlementPort, Clock.systemUTC(), lease, retryDelay, maxAttempts);
    }

    DisputeSettlementService(DisputeResolutionStore store, BookingResolutionPort bookingResolutionPort,
                             DisputeSettlementPort settlementPort, Clock clock, Duration lease, Duration retryDelay,
                             int maxAttempts) {
        if (lease.isNegative() || lease.isZero() || retryDelay.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid app.dispute.settlement settings: need lease > 0, "
                    + "retry-delay >= 0 and max-attempts >= 1");
        }
        this.store = store;
        this.bookingResolutionPort = bookingResolutionPort;
        this.settlementPort = settlementPort;
        this.clock = clock;
        this.lease = lease;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Settles one dispute right after it became final, if no other worker holds it. Never throws: whatever happens,
     * the decision stays and the finalizer retries what is still PENDING or FAILED.
     */
    public Optional<SettlementState> settle(String disputeId) {
        try {
            Instant now = clock.instant();
            return store.claimSettlement(disputeId, now, lease, now.minus(retryDelay), maxAttempts)
                    .map(claimed -> process(claimed, now));
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SETTLEMENT_DEFERRED disputeId={} cause={}", disputeId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Settles up to {@code batchSize} disputes that are PENDING, or FAILED and due for a retry. Returns how many. */
    public int settleDue(int batchSize) {
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            Instant now = clock.instant();
            Optional<Dispute> claimed = store.claimSettlement(null, now, lease, now.minus(retryDelay), maxAttempts);
            if (claimed.isEmpty()) {
                break;
            }
            process(claimed.get(), now);
            processed++;
        }
        return processed;
    }

    private SettlementState process(Dispute dispute, Instant now) {
        DisputeSettlement settlement = dispute.getSettlement();
        SettlementInstruction instruction = SettlementInstruction.from(dispute.getId(), dispute.getBookingId(),
                dispute.getCustomerId(), dispute.getProviderId(), dispute.getFinalDecision());

        SettlementState bookingState = needsCall(settlement.getBookingState())
                ? call("booking", dispute, () -> bookingResolutionPort.resolveBooking(instruction))
                : settlement.getBookingState();
        SettlementState payoutState = needsCall(settlement.getPayoutState())
                ? call("payout", dispute, () -> settlementPort.settle(instruction))
                : settlement.getPayoutState();
        SettlementState overall = DisputeSettlement.overall(bookingState, payoutState);

        try {
            if (!store.recordSettlement(dispute.getId(), settlement.getLeaseOwner(), bookingState, payoutState, now)) {
                log.warn("event=DISPUTE_SETTLEMENT_NOT_RECORDED reason=LEASE_LOST disputeId={}", dispute.getId());
            }
        } catch (RuntimeException e) {
            // The lease expires and the attempt is repeated with the same idempotency key.
            log.error("event=DISPUTE_SETTLEMENT_NOT_RECORDED disputeId={} cause={}",
                    dispute.getId(), e.getClass().getSimpleName());
        }
        log.info("event=DISPUTE_SETTLEMENT_ATTEMPTED disputeId={} bookingId={} state={} bookingState={} payoutState={}",
                dispute.getId(), dispute.getBookingId(), overall, bookingState, payoutState);
        return overall;
    }

    private static boolean needsCall(SettlementState state) {
        return state == null || state == SettlementState.PENDING || state == SettlementState.FAILED;
    }

    private static SettlementState call(String part, Dispute dispute, Supplier<SettlementOutcome> port) {
        try {
            SettlementOutcome outcome = port.get();
            return outcome == SettlementOutcome.APPLIED ? SettlementState.APPLIED : SettlementState.NOT_APPLIED;
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SETTLEMENT_FAILED part={} disputeId={} bookingId={} cause={}",
                    part, dispute.getId(), dispute.getBookingId(), e.getClass().getSimpleName());
            return SettlementState.FAILED;
        }
    }
}
