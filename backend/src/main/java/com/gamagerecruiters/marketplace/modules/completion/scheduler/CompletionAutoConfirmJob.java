package com.gamagerecruiters.marketplace.modules.completion.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.completion.service.ServiceCompletionService;

/**
 * 48-hour auto-confirm (FR-COMP-005): every 15 minutes (configurable), completions that were
 * marked completed more than 48 hours ago, with no confirmation and no issue, are confirmed
 * as SYSTEM and the booking moves to COMPLETED. One failing booking does not stop the others;
 * it is retried on the next run.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.completion.auto-confirm.enabled", havingValue = "true", matchIfMissing = true)
public class CompletionAutoConfirmJob {

    private static final Logger log = LoggerFactory.getLogger(CompletionAutoConfirmJob.class);

    private final ServiceCompletionService completionService;
    private final Clock clock;

    @Autowired
    public CompletionAutoConfirmJob(ServiceCompletionService completionService) {
        this(completionService, Clock.systemUTC());
    }

    CompletionAutoConfirmJob(ServiceCompletionService completionService, Clock clock) {
        this.completionService = completionService;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.completion.auto-confirm.interval-ms:900000}",
            initialDelayString = "${app.completion.auto-confirm.initial-delay-ms:60000}")
    public void run() {
        runOnce();
    }

    /** Returns the number of bookings confirmed in this run. */
    int runOnce() {
        Instant now = clock.instant();
        List<String> bookingIds = completionService.findAutoConfirmCandidates(now);
        int confirmed = 0;
        for (String bookingId : bookingIds) {
            try {
                if (completionService.autoConfirm(bookingId)) {
                    confirmed++;
                }
            } catch (RuntimeException ex) {
                log.warn("Auto-confirm failed for booking {}: {}", bookingId, ex.getMessage());
            }
        }
        if (!bookingIds.isEmpty()) {
            log.info("Auto-confirm run: {} due, {} confirmed", bookingIds.size(), confirmed);
        }
        return confirmed;
    }
}
