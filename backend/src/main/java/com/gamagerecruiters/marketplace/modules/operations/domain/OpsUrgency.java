package com.gamagerecruiters.marketplace.modules.operations.domain;

import java.time.Duration;

/** Late-start urgency by how long after the scheduled start the booking still has not started. */
public enum OpsUrgency {
    MEDIUM,
    HIGH,
    CRITICAL;

    /** MEDIUM &lt; 2h, HIGH 2–4h, CRITICAL &gt; 4h. */
    public static OpsUrgency of(long minutesLate) {
        Duration late = Duration.ofMinutes(Math.max(0, minutesLate));
        if (late.toHours() > 4) {
            return CRITICAL;
        }
        if (late.toHours() >= 2) {
            return HIGH;
        }
        return MEDIUM;
    }
}
