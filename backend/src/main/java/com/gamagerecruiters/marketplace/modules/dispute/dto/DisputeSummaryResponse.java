package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;

/** Short dispute view for list endpoints. */
public record DisputeSummaryResponse(
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
        Instant updatedAt
) {
}
