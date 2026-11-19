package com.gamagerecruiters.marketplace.modules.announcement.dto;

/** What the admin wants on create/update: keep it as a draft, or publish (now, or at scheduledAt). */
public enum AnnouncementIntent {
    DRAFT,
    PUBLISH
}
