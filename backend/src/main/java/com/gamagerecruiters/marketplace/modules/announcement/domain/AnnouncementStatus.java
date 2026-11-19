package com.gamagerecruiters.marketplace.modules.announcement.domain;

/**
 * Announcement lifecycle: DRAFT -> PUBLISHED (publish now), or DRAFT/new -> SCHEDULED -> PUBLISHED (scheduled
 * publisher). PUBLISHED is final: it cannot be deleted, and only its title and message can be corrected.
 */
public enum AnnouncementStatus {
    DRAFT,
    SCHEDULED,
    PUBLISHED
}
