package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;

/**
 * Scheduled finalization:
 * <ol>
 *   <li>RESOLVED disputes whose appeal window has passed without an appeal become RESOLVED_FINAL (the original
 *       decision becomes binding). Each change is one conditional update that repeats every rule plus the version,
 *       so it happens exactly once even with several instances running.</li>
 *   <li>Settlement of final disputes that are still PENDING, or FAILED and due for a retry, through
 *       {@link DisputeSettlementService} (lease per dispute, idempotent).</li>
 * </ol>
 * Settings (defaults in brackets): {@code app.dispute.finalizer.enabled} [true], {@code .interval} [PT5M],
 * {@code .initial-delay} [PT1M], {@code .batch-size} [50]. Only ids are logged.
 */
@Component
public class DisputeFinalizer {

    private static final Logger log = LoggerFactory.getLogger(DisputeFinalizer.class);

    /** Actor recorded in the status history for system changes. */
    static final String SYSTEM_ACTOR = "SYSTEM";
    static final String FINALIZE_REASON = "Appeal window closed without an appeal";

    /** What one run did. */
    public record Result(int finalized, int settled) {
    }

    private final DisputeResolutionStore store;
    private final DisputeSettlementService settlementService;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    public DisputeFinalizer(DisputeResolutionStore store,
                            DisputeSettlementService settlementService,
                            @Value("${app.dispute.finalizer.enabled:true}") boolean enabled,
                            @Value("${app.dispute.finalizer.batch-size:50}") int batchSize) {
        this(store, settlementService, Clock.systemUTC(), enabled, batchSize);
    }

    DisputeFinalizer(DisputeResolutionStore store, DisputeSettlementService settlementService, Clock clock,
                     boolean enabled, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.dispute.finalizer.batch-size must be at least 1");
        }
        this.store = store;
        this.settlementService = settlementService;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    /** Runs only when scheduling is enabled (DisputeSchedulingConfig) and the finalizer is not switched off. */
    @Scheduled(fixedDelayString = "${app.dispute.finalizer.interval:PT5M}",
            initialDelayString = "${app.dispute.finalizer.initial-delay:PT1M}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            Result result = run();
            if (result.finalized() + result.settled() > 0) {
                log.info("event=DISPUTE_FINALIZER_RUN finalized={} settled={}", result.finalized(), result.settled());
            }
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_FINALIZER_FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    /** One pass: finalize up to {@code batch-size} expired decisions, then settle up to {@code batch-size}. */
    public Result run() {
        Instant now = clock.instant();
        int finalized = 0;
        for (Dispute candidate : store.findExpiredResolved(now, batchSize)) {
            StatusHistoryEntry entry = new StatusHistoryEntry(DisputeStatus.RESOLVED, DisputeStatus.RESOLVED_FINAL,
                    SYSTEM_ACTOR, null, FINALIZE_REASON, now);
            if (store.finalizeExpired(candidate, now, entry).isPresent()) {
                finalized++;
                log.info("event=DISPUTE_FINALIZED disputeId={} bookingId={}", candidate.getId(), candidate.getBookingId());
            }
        }
        int settled = settlementService.settleDue(batchSize);
        return new Result(finalized, settled);
    }
}
