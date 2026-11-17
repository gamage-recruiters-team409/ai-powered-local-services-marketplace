package com.gamagerecruiters.marketplace.modules.dispute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the dispute booking-sync reconciler (DisputeBookingSyncReconciler). Off in the
 * "test" profile so unit and web tests never run background jobs. It can also be switched off at runtime with
 * {@code app.dispute.booking-sync.enabled=false}.
 *
 * <p>Note: @EnableScheduling is application-wide. No other module uses @Scheduled at the time of writing.</p>
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class DisputeSchedulingConfig {
}
