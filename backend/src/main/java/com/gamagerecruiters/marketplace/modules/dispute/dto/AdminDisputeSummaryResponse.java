package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;

/**
 * Admin list row: the same fields as {@link DisputeSummaryResponse} plus the read-only settlement state and manual
 * review reason (both null before the dispute is final). Never used for party lists.
 */
public record AdminDisputeSummaryResponse(
        String id,
        String bookingId,
        String customerId,
        String providerId,
        Role raisedByRole,
        DisputeCategory category,
        DisputeStatus status,
        Instant responseDueAt,
        Instant appealDeadline,
        Instant createdAt,
        Instant updatedAt,
        SettlementState settlementState,
        String manualReviewReason
) {
}
