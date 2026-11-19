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
 * <p>When WP09 has no case for the booking (opening it failed), nothing is changed here: opening a case later is
 * coordinated with the WP09 owner. The customer can also open it from the dispute pages.
 *
 * <p>Settings (defaults in brackets): {@code app.completion.dispute-link.min-age} [PT2M],
 * {@code .max-age} [P14D], {@code .batch-size} [50].
 */
@Component
public class CompletionDisputeLinkReconciler {

    private static final Logger log = LoggerFactory.getLogger(CompletionDisputeLinkReconciler.class);

    private final MongoTemplate mongoTemplate;
    private final CompletionAtomicUpdates atomicUpdates;
    private final CompletionDisputes disputes;
    private final Clock clock;
    private final Duration minAge;
    private final Duration maxAge;
    private final int batchSize;

    @Autowired
    public CompletionDisputeLinkReconciler(MongoTemplate mongoTemplate, CompletionAtomicUpdates atomicUpdates,
                                           CompletionDisputes disputes,
                                           @Value("${app.completion.dispute-link.min-age:PT2M}") Duration minAge,
                                           @Value("${app.completion.dispute-link.max-age:P14D}") Duration maxAge,
                                           @Value("${app.completion.dispute-link.batch-size:50}") int batchSize) {
        this(mongoTemplate, atomicUpdates, disputes, Clock.systemUTC(), minAge, maxAge, batchSize);
    }

    CompletionDisputeLinkReconciler(MongoTemplate mongoTemplate, CompletionAtomicUpdates atomicUpdates,
                                    CompletionDisputes disputes, Clock clock,
                                    Duration minAge, Duration maxAge, int batchSize) {
        this.mongoTemplate = mongoTemplate;
        this.atomicUpdates = atomicUpdates;
        this.disputes = disputes;
        this.clock = clock;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.batchSize = Math.max(batchSize, 1);
    }

    /** One pass. Returns the number of dispute links stored. One failing booking does not stop the others. */
    public int reconcileOnce() {
        Instant now = clock.instant();
        Query missingLinks = new Query(Criteria.where("disputeId").is(null)
                .and("issueRaisedAt").lte(now.minus(minAge)).gte(now.minus(maxAge)))
                .with(Sort.by(Sort.Direction.ASC, "issueRaisedAt"))
                .limit(batchSize);
        List<ServiceCompletion> candidates = mongoTemplate.find(missingLinks, ServiceCompletion.class);

        int linked = 0;
        for (ServiceCompletion completion : candidates) {
            try {
                Optional<String> disputeId = disputes.findDisputeId(completion.getBookingId(), completion.getCustomerId());
                if (disputeId.isPresent()
                        && atomicUpdates.recordDisputeId(completion.getBookingId(), disputeId.get(), clock.instant()).isPresent()) {
                    linked++;
                    log.info("event=COMPLETION_DISPUTE_LINK_RECOVERED bookingId={} disputeId={}",
                            completion.getBookingId(), disputeId.get());
                }
            } catch (RuntimeException ex) {
                log.warn("event=COMPLETION_DISPUTE_LINK_RETRY_FAILED bookingId={} cause={}",
                        completion.getBookingId(), ex.getClass().getSimpleName());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("Dispute link reconcile: {} without a link, {} linked", candidates.size(), linked);
        }
        return linked;
    }
}
