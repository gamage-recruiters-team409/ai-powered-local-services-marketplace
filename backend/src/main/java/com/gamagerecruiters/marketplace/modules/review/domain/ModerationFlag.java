package com.gamagerecruiters.marketplace.modules.review.domain;

/**
 * Why the automated checks held a review for a moderator (FR-REV-003).
 * A flag never removes a review by itself: a moderator makes the final decision (BR-016).
 */
public enum ModerationFlag {
    /** Phone number or e-mail address in the text. */
    CONTACT_DETAILS,
    /** Web link or domain name in the text. */
    LINK,
    /** A word or phrase from the configured blocked list. */
    BLOCKED_WORDS
}
