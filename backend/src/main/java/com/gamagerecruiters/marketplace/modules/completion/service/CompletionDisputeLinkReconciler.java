package com.gamagerecruiters.marketplace.modules.completion.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;

/**
 * Repairs a missing dispute link after Raise Issue (WP08/WP09).
 *
 * <p>Raise Issue commits the issue and the DISPUTED booking first, then opens the WP09 case, then stores the case
 * ID on the completion. If that last write fails, the issue and the case still exist but the completion has no
 * {@code disputeId}. This reconciler finds such completions (issue raised, no dispute ID, older than
 * {@code min-age} so in-flight requests are left alone, younger than {@code max-age}) and looks the case up in WP09
 * through {@link CompletionDisputes#findDisputeId} (read-only). It never opens a dispute, so a case can never be
 * created twice. The link is stored with the same conditional update (only while no ID is stored).
 *
 * <p>Fair retries (no batch starvation): when a record cannot be linked (WP09 has no case yet, or the lookup
 * failed), its attempt count goes up and {@code disputeLinkNextAttemptAt} is set with an exponential backoff
 * ({@code retry-base} doubled per attempt, at most {@code retry-max}). A batch only takes records that are due
 * ({@code disputeLinkNextAttemptAt} missing or in the past); records never tried come first, then the ones
 * waiting longest. So unresolved records step aside after each attempt and newer recoverable links are always
 * reached, while unresolved ones are still retried later until {@code max-age}.
 *
 * <p>When WP09 has no case for the booking (opening it failed), nothing else is changed here: opening a case later
 * is coordinated with the WP09 owner.
 *
 * <p>Settings (defaults in brackets): {@code app.completion.dispute-link.min-age} [PT2M], {@code .max-age} [P14D],
 * {@code .batch-size} [50], {@code .retry-base} [PT10M], {@code .retry-max} [PT6H].
 */
@Component
public class CompletionDisputeLinkReconciler {

    private static final Logger log = LoggerFactory.getLogger(CompletionDisputeLinkReconciler.class);

    static final String NEXT_ATTEMPT = "disputeLinkNextAttemptAt";

    private final MongoTemplate mongoTemplate;
    private final CompletionAtomicUpdates atomicUpdates;
    private final CompletionDisputes disputes;
    private final Clock clock;
    private final Duration minAge;
    private final Duration maxAge;
    private final int batchSize;
    private final Duration retryBase;
    private final Duration retryMax;

    @Autowired
    public CompletionDisputeLinkReconciler(MongoTemplate mongoTemplate, CompletionAtomicUpdates atomicUpdates,
                                           CompletionDisputes disputes,
                                           @Value("${app.completion.dispute-link.min-age:PT2M}") Duration minAge,
                                           @Value("${app.completion.dispute-link.max-age:P14D}") Duration maxAge,
                                           @Value("${app.completion.dispute-link.batch-size:50}") int batchSize,
                                           @Value("${app.completion.dispute-link.retry-base:PT10M}") Duration retryBase,
                                           @Value("${app.completion.dispute-link.retry-max:PT6H}") Duration retryMax) {
        this(mongoTemplate, atomicUpdates, disputes, Clock.systemUTC(), minAge, maxAge, batchSize, retryBase, retryMax);
    }

    CompletionDisputeLinkReconciler(MongoTemplate mongoTemplate, CompletionAtomicUpdates atomicUpdates,
                                    CompletionDisputes disputes, Clock clock, Duration minAge, Duration maxAge,
                                    int batchSize, Duration retryBase, Duration retryMax) {
        this.mongoTemplate = mongoTemplate;
        this.atomicUpdates = atomicUpdates;
        this.disputes = disputes;
        this.clock = clock;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.batchSize = Math.max(batchSize, 1);
        this.retryBase = retryBase;
        this.retryMax = retryMax;
    }

    /** One pass. Returns the number of dispute links stored. One failing booking does not stop the others. */
    public int reconcileOnce() {
        Instant now = clock.instant();
        Criteria due = new Criteria().orOperator(
                Criteria.where(NEXT_ATTEMPT).is(null),
                Criteria.where(NEXT_ATTEMPT).lte(now));
        Query missingLinks = new Query(new Criteria().andOperator(
                Criteria.where("disputeId").is(null),
                Criteria.where("issueRaisedAt").lte(now.minus(minAge)).gte(now.minus(maxAge)),
                due))
                // Never-tried records first (missing field sorts first), then the ones waiting longest
                .with(Sort.by(Sort.Order.asc(NEXT_ATTEMPT), Sort.Order.asc("issueRaisedAt")))
                .limit(batchSize);
        List<ServiceCompletion> candidates = mongoTemplate.find(missingLinks, ServiceCompletion.class);

        int linked = 0;
        for (ServiceCompletion completion : candidates) {
            String bookingId = completion.getBookingId();
            try {
                Optional<String> disputeId = disputes.findDisputeId(bookingId, completion.getCustomerId());
                if (disputeId.isPresent()
                        && atomicUpdates.recordDisputeId(bookingId, disputeId.get(), clock.instant()).isPresent()) {
                    linked++;
                    log.info("event=COMPLETION_DISPUTE_LINK_RECOVERED bookingId={} disputeId={}", bookingId, disputeId.get());
                } else if (disputeId.isEmpty()) {
                    scheduleRetry(completion, "NO_WP09_CASE");
                }
            } catch (RuntimeException ex) {
                log.warn("event=COMPLETION_DISPUTE_LINK_RETRY_FAILED bookingId={} cause={}",
                        bookingId, ex.getClass().getSimpleName());
                scheduleRetry(completion, ex.getClass().getSimpleName());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("Dispute link reconcile: {} due without a link, {} linked", candidates.size(), linked);
        }
        return linked;
    }

    /** Moves an unresolved record behind the others until its backoff has passed. Best effort. */
    private void scheduleRetry(ServiceCompletion completion, String reason) {
        try {
            Instant now = clock.instant();
            Instant next = now.plus(retryDelay(completion.getDisputeLinkAttempts(), retryBase, retryMax));
            atomicUpdates.scheduleDisputeLinkRetry(completion.getBookingId(), next, now);
            log.info("event=COMPLETION_DISPUTE_LINK_RETRY_SCHEDULED bookingId={} attempt={} nextAttemptAt={} reason={}",
                    completion.getBookingId(), completion.getDisputeLinkAttempts() + 1, next, reason);
        } catch (RuntimeException ex) {
            // Not stored: the record stays due and is tried again in a later pass
            log.warn("event=COMPLETION_DISPUTE_LINK_RETRY_NOT_SCHEDULED bookingId={} cause={}",
                    completion.getBookingId(), ex.getClass().getSimpleName());
        }
    }

    /** base × 2^previousAttempts, never more than max. */
    static Duration retryDelay(int previousAttempts, Duration base, Duration max) {
        int shift = Math.min(Math.max(previousAttempts, 0), 20);
        Duration delay = base.multipliedBy(1L << shift);
        return delay.compareTo(max) > 0 ? max : delay;
    }
}
