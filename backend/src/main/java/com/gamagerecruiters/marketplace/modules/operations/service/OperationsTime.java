package com.gamagerecruiters.marketplace.modules.operations.service;

import java.time.Instant;
import java.time.LocalDate;

import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;

/** Asia/Colombo day-boundary helpers for "today" and per-day windows. */
final class OperationsTime {

    private OperationsTime() {
    }

    static LocalDate today(Instant now) {
        return now.atZone(OperationsRules.ZONE).toLocalDate();
    }

    static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(OperationsRules.ZONE).toInstant();
    }

    /** Start of the Asia/Colombo day containing {@code now}. */
    static Instant todayStart(Instant now) {
        return startOfDay(today(now));
    }

    /** Start of the day after the Asia/Colombo day containing {@code now}. */
    static Instant tomorrowStart(Instant now) {
        return startOfDay(today(now).plusDays(1));
    }
}
