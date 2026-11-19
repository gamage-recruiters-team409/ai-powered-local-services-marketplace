package com.gamagerecruiters.marketplace.modules.completion.service;

import java.util.Optional;

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

    /**
     * Read-only: finds the ID of the dispute case that already exists for the booking, as seen by one of its
     * parties. Used to repair a missing dispute link without opening a second case.
     *
     * @return the dispute ID, or empty when there is none or it cannot be read now
     */
    default Optional<String> findDisputeId(String bookingId, String partyId) {
        return Optional.empty();
    }

    /** No dispute cases (unit tests that do not cover the dispute link). */
    static CompletionDisputes none() {
        return (bookingId, actorId, actorRole, category, description) -> null;
    }
}
