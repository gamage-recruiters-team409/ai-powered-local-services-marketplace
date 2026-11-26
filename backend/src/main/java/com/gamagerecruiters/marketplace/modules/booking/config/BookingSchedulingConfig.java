package com.gamagerecruiters.marketplace.modules.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on scheduling for the booking expiry job (BookingExpiryJob). Off in the "test" profile so unit and
 * web tests never run background jobs. @EnableScheduling is application-wide and idempotent.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class BookingSchedulingConfig {
}
