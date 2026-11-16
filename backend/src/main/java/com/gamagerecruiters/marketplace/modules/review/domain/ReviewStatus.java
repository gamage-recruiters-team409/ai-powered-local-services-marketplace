package com.gamagerecruiters.marketplace.modules.review.domain;

/**
 * Review lifecycle (FR-REV-003).
 * AWAITING_MODERATION - flagged by automated checks, waiting for a moderator.
 * PUBLISHED           - visible on public pages; a user report does NOT change this status.
 * REMOVED             - hidden by a moderator decision.
 */
public enum ReviewStatus {
    AWAITING_MODERATION,
    PUBLISHED,
    REMOVED
}
