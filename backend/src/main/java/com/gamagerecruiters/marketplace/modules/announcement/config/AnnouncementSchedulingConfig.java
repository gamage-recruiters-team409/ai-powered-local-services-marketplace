package com.gamagerecruiters.marketplace.modules.announcement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the scheduled announcement publisher (AnnouncementPublisher). Off in the "test"
 * profile so unit and web tests never run background jobs; the publisher itself can be switched off with
 * {@code app.announcement.publisher.enabled=false}. @EnableScheduling is application-wide and idempotent.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class AnnouncementSchedulingConfig {
}
