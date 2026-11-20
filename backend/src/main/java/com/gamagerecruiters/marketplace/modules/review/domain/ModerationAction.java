package com.gamagerecruiters.marketplace.modules.review.domain;

/** Entries in a review's moderation history (BR-014: moderation actions are recorded). */
public enum ModerationAction {
    /** The automated checks held the review when it was posted or edited (actor: SYSTEM). */
    AUTO_FLAGGED,
    /** A moderator published a held review. */
    PUBLISHED,
    /** A moderator kept a reported review published and closed its reports. */
    REPORTS_DISMISSED,
    /** A moderator removed the review. */
    REMOVED
}
