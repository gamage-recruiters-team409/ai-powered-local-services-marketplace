package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;

/** Full dispute view for the parties and authorized admins. */
public record DisputeDetailResponse(
        String id,
        String bookingId,
        String customerId,
        String providerId,
        String raisedBy,
        Role raisedByRole,
        DisputeCategory category,
        String description,
        List<String> evidence,
        DisputeStatus status,
        PartyResponseView response,
        Instant responseDueAt,
        Instant createdAt,
        Instant updatedAt,
        List<StatusHistoryView> statusHistory
) {

    public record PartyResponseView(String responderId, String text, List<String> evidence, Instant respondedAt) {
    }

    public record StatusHistoryView(
            DisputeStatus fromStatus,
            DisputeStatus toStatus,
            String actorId,
            Role actorRole,
            String reason,
            Instant timestamp
    ) {
    }
}
