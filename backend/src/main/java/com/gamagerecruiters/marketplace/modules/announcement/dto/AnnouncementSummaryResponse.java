package com.gamagerecruiters.marketplace.modules.announcement.dto;

/**
 * Admin dashboard counts. {@code active} = PUBLISHED, pinned to the header and pinExpiresAt in the future;
 * {@code scheduled} = SCHEDULED; {@code total} = all. {@code tabs} are the list tab counts.
 */
public record AnnouncementSummaryResponse(
        long active,
        long scheduled,
        long total,
        Tabs tabs
) {

    public record Tabs(long all, long published, long scheduled, long drafts) {
    }
}
