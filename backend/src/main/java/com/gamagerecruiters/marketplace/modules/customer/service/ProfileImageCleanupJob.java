package com.gamagerecruiters.marketplace.modules.customer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled background job for processing pending profile image cleanup tasks.
 * Follows the project's background job conventions (modelled on ProviderVerificationExpiryJob and DisputeFinalizer).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
        name = "app.customer.profile-image-cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProfileImageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanupJob.class);

    private final ProfileImageCleanupService cleanupService;
    private final int batchSize;

    public ProfileImageCleanupJob(
            ProfileImageCleanupService cleanupService,
            @Value("${app.customer.profile-image-cleanup.batch-size:25}") int batchSize,
            @Value("${app.customer.profile-image-cleanup.interval:PT5M}") String interval
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.customer.profile-image-cleanup.batch-size must be at least 1");
        }
        this.cleanupService = cleanupService;
        this.batchSize = batchSize;
        log.info("event=PROFILE_IMAGE_CLEANUP_JOB_ENABLED interval={} batchSize={}", interval, batchSize);
    }

    @Scheduled(
            fixedDelayString = "${app.customer.profile-image-cleanup.interval:PT5M}",
            initialDelayString = "${app.customer.profile-image-cleanup.initial-delay:PT1M}"
    )
    public void run() {
        try {
            int processed = cleanupService.retryPending(batchSize);
            if (processed > 0) {
                log.info("event=PROFILE_IMAGE_CLEANUP_RUN processed={}", processed);
            } else {
                log.debug("event=PROFILE_IMAGE_CLEANUP_RUN processed=0");
            }
        } catch (RuntimeException ex) {
            log.error("Profile image cleanup job failed: error={}", ex.getClass().getSimpleName());
        }
    }
}
