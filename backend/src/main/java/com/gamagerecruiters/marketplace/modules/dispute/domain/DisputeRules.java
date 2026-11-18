package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Duration;

/**
 * Dispute business rules, kept in one place. Plain constants for now; they may move to platform
 * settings (WP09 settings) later without changing callers.
 */
public final class DisputeRules {

    // No raise window here: when a booking may be disputed (status, 72 hours after completion, BR-008) is
    // decided by the Booking module's BookingFacade.markDisputed.

    /** The other party has 72 hours after the dispute is raised to respond. */
    public static final Duration RESPONSE_WINDOW = Duration.ofHours(72);

    /** Appeal window after an admin decision: one appeal per dispute within this time. */
    public static final Duration APPEAL_WINDOW = Duration.ofHours(48);

    /** Provider penalty percentage, applied only together with a full or partial refund to the customer. */
    public static final int PROVIDER_PENALTY_PERCENT = 30;

    /** Customer refund percentage bounds for a partial refund (full refund = 100, release to provider = 0). */
    public static final int MIN_PARTIAL_REFUND_PERCENT = 1;
    public static final int MAX_PARTIAL_REFUND_PERCENT = 99;

    /** Admin decision notes (internal, never shown to the parties) and appeal grounds. */
    public static final int MAX_ADMIN_NOTE_LENGTH = 1000;
    public static final int MAX_APPEAL_GROUNDS_LENGTH = 2000;

    /** Admin list search text (an id or the start of one). */
    public static final int MAX_SEARCH_TEXT_LENGTH = 64;

    /** "Resolved" count window of the admin summary. */
    public static final Duration SUMMARY_RESOLVED_WINDOW = Duration.ofDays(30);

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
