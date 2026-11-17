package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;

/**
 * Completes or removes dispute intent records (bookingSyncState = PENDING) left behind when a raise request stopped
 * between "booking moved to DISPUTED" and "dispute marked SYNCED":
 * <ul>
 *   <li>booking is DISPUTED: the dispute is marked SYNCED (it becomes visible);</li>
 *   <li>booking is not DISPUTED (or no longer exists) and the record is older than {@code delete-after}: removed;</li>
 *   <li>otherwise left alone for a later run.</li>
 * </ul>
 * Safe on several instances: each record is claimed with an atomic findAndModify lease, and every completion or
 * removal is conditional on the record still being PENDING. Only ids are logged.
 *
 * <p>Settings (defaults in brackets): {@code app.dispute.booking-sync.enabled} [true], {@code .interval} [PT5M],
 * {@code .initial-delay} [PT1M], {@code .min-age} [PT2M] (younger records may still be in flight),
 * {@code .delete-after} [PT15M], {@code .lease} [PT5M], {@code .batch-size} [50].</p>
 */
@Component
public class DisputeBookingSyncReconciler {

    private static final Logger log = LoggerFactory.getLogger(DisputeBookingSyncReconciler.class);

    /** What one run did. */
    public record Result(int synced, int deleted, int skipped) {
    }

    private final DisputeBookingSyncStore syncStore;
    private final BookingLookupPort bookingLookupPort;
    private final Clock clock;
    private final boolean enabled;
    private final Duration minAge;
    private final Duration deleteAfter;
    private final Duration lease;
    private final int batchSize;

    @Autowired
    public DisputeBookingSyncReconciler(
            DisputeBookingSyncStore syncStore,
            BookingLookupPort bookingLookupPort,
            @Value("${app.dispute.booking-sync.enabled:true}") boolean enabled,
            @Value("${app.dispute.booking-sync.min-age:PT2M}") Duration minAge,
            @Value("${app.dispute.booking-sync.delete-after:PT15M}") Duration deleteAfter,
            @Value("${app.dispute.booking-sync.lease:PT5M}") Duration lease,
            @Value("${app.dispute.booking-sync.batch-size:50}") int batchSize) {
        this(syncStore, bookingLookupPort, Clock.systemUTC(), enabled, minAge, deleteAfter, lease, batchSize);
    }

    DisputeBookingSyncReconciler(DisputeBookingSyncStore syncStore, BookingLookupPort bookingLookupPort, Clock clock,
                                 boolean enabled, Duration minAge, Duration deleteAfter, Duration lease, int batchSize) {
        if (minAge.isNegative() || deleteAfter.compareTo(minAge) < 0 || lease.isNegative() || lease.isZero()
                || batchSize < 1) {
            throw new IllegalArgumentException("Invalid app.dispute.booking-sync settings: need 0 <= min-age <= "
                    + "delete-after, lease > 0 and batch-size >= 1");
        }
        this.syncStore = syncStore;
        this.bookingLookupPort = bookingLookupPort;
        this.clock = clock;
        this.enabled = enabled;
        this.minAge = minAge;
        this.deleteAfter = deleteAfter;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    /** Runs only when scheduling is enabled (DisputeSchedulingConfig) and the reconciler is not switched off. */
    @Scheduled(fixedDelayString = "${app.dispute.booking-sync.interval:PT5M}",
            initialDelayString = "${app.dispute.booking-sync.initial-delay:PT1M}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            Result result = reconcile();
            if (result.synced() + result.deleted() + result.skipped() > 0) {
                log.info("event=DISPUTE_SYNC_RECONCILED synced={} deleted={} skipped={}",
                        result.synced(), result.deleted(), result.skipped());
            }
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SYNC_RECONCILE_FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    /** One pass over at most {@code batch-size} claimable PENDING records. */
    public Result reconcile() {
        int synced = 0;
        int deleted = 0;
        int skipped = 0;
        for (int i = 0; i < batchSize; i++) {
            Instant now = clock.instant();
            Optional<Dispute> claimed = syncStore.claimPending(now.minus(minAge), now, lease);
            if (claimed.isEmpty()) {
                break;
            }
            switch (reconcileOne(claimed.get(), now)) {
                case SYNCED -> synced++;
                case DELETED -> deleted++;
                default -> skipped++;
            }
        }
        return new Result(synced, deleted, skipped);
    }

    private enum Outcome { SYNCED, DELETED, SKIPPED }

    private Outcome reconcileOne(Dispute intent, Instant now) {
        String disputeId = intent.getId();
        String bookingId = intent.getBookingId();

        boolean bookingDisputed;
        try {
            BookingSnapshot booking = bookingLookupPort.getBooking(bookingId);
            bookingDisputed = booking.alreadyDisputed();
        } catch (ResourceNotFoundException e) {
            bookingDisputed = false;
        } catch (RuntimeException e) {
            // Booking module unavailable: leave it; the lease expires and a later run retries.
            log.warn("event=DISPUTE_SYNC_LOOKUP_FAILED disputeId={} bookingId={} cause={}",
                    disputeId, bookingId, e.getClass().getSimpleName());
            return Outcome.SKIPPED;
        }

        if (bookingDisputed) {
            if (syncStore.markSynced(disputeId).isPresent()) {
                log.info("event=DISPUTE_SYNC_COMPLETED disputeId={} bookingId={}", disputeId, bookingId);
                return Outcome.SYNCED;
            }
            return Outcome.SKIPPED;
        }

        Instant createdAt = intent.getCreatedAt();
        if (createdAt != null && !createdAt.isAfter(now.minus(deleteAfter))) {
            if (syncStore.deleteIfPending(disputeId)) {
                log.info("event=DISPUTE_INTENT_REMOVED disputeId={} bookingId={}", disputeId, bookingId);
                return Outcome.DELETED;
            }
        }
        return Outcome.SKIPPED;
    }
}
