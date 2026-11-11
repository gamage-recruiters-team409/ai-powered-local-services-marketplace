package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Duration;

/**
 * Dispute business rules, kept in one place. Plain constants for now; they may move to platform
 * settings (WP09 settings) later without changing callers.
 */
public final class DisputeRules {

    /** A party can raise a dispute within 72 hours after the booking slot ends. */
    public static final Duration RAISE_WINDOW = Duration.ofHours(72);

    /** The other party has 72 hours after the dispute is raised to respond. */
    public static final Duration RESPONSE_WINDOW = Duration.ofHours(72);

    /** Appeal window after a decision. Constant only; appeal logic comes in a later PR. */
    public static final Duration APPEAL_WINDOW = Duration.ofHours(48);

    /** Provider penalty percentage. Constant only; penalty logic comes in a later PR. */
    public static final int PROVIDER_PENALTY_PERCENT = 30;

    /** Limits for request content. */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_EVIDENCE_ITEMS = 10;
    public static final int MAX_EVIDENCE_REFERENCE_LENGTH = 500;

    /** Paging limits for list endpoints. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private DisputeRules() {
    }
}
