package com.gamagerecruiters.marketplace.modules.announcement.domain;

import java.time.Duration;

/** Announcement limits and rules in one place. */
public final class AnnouncementRules {

    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_MESSAGE_LENGTH = 5000;
    public static final int MAX_SEARCH_LENGTH = 80;

    /** "Publish" with a scheduledAt more than this far in the future schedules it; otherwise it is published now. */
    public static final Duration SCHEDULE_THRESHOLD = Duration.ofMinutes(1);

    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 100;

    private AnnouncementRules() {
    }
}
