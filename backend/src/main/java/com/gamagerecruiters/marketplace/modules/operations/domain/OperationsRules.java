package com.gamagerecruiters.marketplace.modules.operations.domain;

import java.time.ZoneId;

/** Shared constants and small pure helpers for the operations module (WP09). */
public final class OperationsRules {

    /** Business day boundary for "today" / per-day counts. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    public static final int NOTE_MIN = 10;
    public static final int NOTE_MAX = 1000;

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** Cap on rows pulled from the booking facade for a single list/scan. */
    public static final int OVERDUE_FETCH_LIMIT = 200;
    public static final int REPEAT_FETCH_LIMIT = 200;

    /** Max rows in any CSV export. */
    public static final int CSV_MAX_ROWS = 5000;

    /** Reason strings for unavailable sections (never accompanied by numbers). */
    public static final String LATE_STARTS_UNAVAILABLE =
            "Late-start tracking is not available yet (WP05 BookingStatsFacade has no late-start query).";

    private OperationsRules() {
    }

    /** Clamp a requested page size to [1, MAX_PAGE_SIZE], defaulting a non-positive value. */
    public static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /** Clamp a requested page index to >= 0. */
    public static int clampPage(int page) {
        return Math.max(page, 0);
    }
}
