package com.gamagerecruiters.marketplace.modules.completion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the 48-hour auto-confirm job.
 * Off in the "test" profile, and can be switched off with app.completion.auto-confirm.enabled=false.
 */
@Configuration
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(name = "app.completion.auto-confirm.enabled", havingValue = "true", matchIfMissing = true)
public class CompletionSchedulingConfig {
}
