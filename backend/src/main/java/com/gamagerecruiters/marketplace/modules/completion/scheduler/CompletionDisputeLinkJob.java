package com.gamagerecruiters.marketplace.modules.completion.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.completion.service.CompletionDisputeLinkReconciler;

/**
 * Every 10 minutes (configurable), stores dispute IDs that could not be saved right after Raise Issue
 * ({@link CompletionDisputeLinkReconciler}). Off in the "test" profile; can be switched off with
 * app.completion.dispute-link.enabled=false.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.completion.dispute-link.enabled", havingValue = "true", matchIfMissing = true)
public class CompletionDisputeLinkJob {

    private final CompletionDisputeLinkReconciler reconciler;

    public CompletionDisputeLinkJob(CompletionDisputeLinkReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            fixedDelayString = "${app.completion.dispute-link.interval-ms:600000}",
            initialDelayString = "${app.completion.dispute-link.initial-delay-ms:120000}")
    public void run() {
        reconciler.reconcileOnce();
    }
}
