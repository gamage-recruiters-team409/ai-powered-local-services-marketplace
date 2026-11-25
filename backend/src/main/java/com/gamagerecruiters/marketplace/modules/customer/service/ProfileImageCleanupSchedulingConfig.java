package com.gamagerecruiters.marketplace.modules.customer.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the profile image cleanup recovery job.
 * Mirrors CompletionSchedulingConfig, DisputeSchedulingConfig, and AnnouncementSchedulingConfig.
 * Inactive under the "test" profile, and can be disabled via app.customer.profile-image-cleanup.enabled=false.
 */
@Configuration
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(
        name = "app.customer.profile-image-cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProfileImageCleanupSchedulingConfig {
}
