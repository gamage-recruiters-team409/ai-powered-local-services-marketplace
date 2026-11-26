package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reschedule rules from the WP05 design (Request Reschedule screen):
 * "You can reschedule 2 more times, at least 6 hours before the start. The other party has 12 hours
 * (or until the original start) to respond."
 */
@Component
public class ReschedulePolicy {

    private final int maxReschedules;
    private final Duration minNoticeBeforeStart;
    private final Duration responseWindow;

    public ReschedulePolicy(@Value("${booking.reschedule.max-count:2}") int maxReschedules,
                            @Value("${booking.reschedule.min-notice-hours:6}") long minNoticeHours,
                            @Value("${booking.reschedule.response-hours:12}") long responseHours) {
        this.maxReschedules = maxReschedules;
        this.minNoticeBeforeStart = Duration.ofHours(minNoticeHours);
        this.responseWindow = Duration.ofHours(responseHours);
    }

    public int maxReschedules() {
        return maxReschedules;
    }

    /** How many more times the booking may be moved. */
    public int remaining(long approvedCount) {
        return (int) Math.max(0, maxReschedules - approvedCount);
    }

    /** A request is allowed only while the current start is at least 6 h away. */
    public boolean isTooLate(Instant currentSlotStart, Instant now) {
        return now.isAfter(currentSlotStart.minus(minNoticeBeforeStart));
    }

    /** 12 h after the request or the current start, whichever comes first. */
    public Instant expiresAt(Instant requestedAt, Instant currentSlotStart) {
        Instant byWindow = requestedAt.plus(responseWindow);
        return byWindow.isBefore(currentSlotStart) ? byWindow : currentSlotStart;
    }
}
