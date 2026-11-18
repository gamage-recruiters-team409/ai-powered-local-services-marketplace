package com.gamagerecruiters.marketplace.modules.completion.service;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;

/**
 * Opens the WP09 dispute case after a raised issue or a no-show report (agreed with Injas):
 * the completion flow first moves the booking to DISPUTED (BookingFacade.markDisputed), then opens the case.
 */
public interface CompletionDisputes {

    /**
     * @return the dispute ID, or null when the case could not be opened now (the issue/no-show itself
     *         is already saved; the reason is logged)
     */
    String openDispute(String bookingId, String actorId, Role actorRole, DisputeCategory category, String description);

    /** No dispute cases (unit tests that do not cover the dispute link). */
    static CompletionDisputes none() {
        return (bookingId, actorId, actorRole, category, description) -> null;
    }
}
