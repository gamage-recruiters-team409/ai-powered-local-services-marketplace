package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleRequest;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleStatus;

/** A reschedule request as the customer/provider sees it. {@code requestedByRole}: CUSTOMER or PROVIDER. */
public record RescheduleView(
        String id,
        RescheduleStatus status,
        String requestedByRole,
        Instant previousSlotStart,
        Instant proposedSlotStart,
        Instant proposedSlotEnd,
        String note,
        Instant requestedAt,
        Instant expiresAt
) {

    public static RescheduleView from(RescheduleRequest r) {
        return r == null ? null : new RescheduleView(r.getId(), r.getStatus(), r.getRequestedByRole(),
                r.getPreviousSlotStart(), r.getProposedSlotStart(), r.getProposedSlotEnd(), r.getNote(),
                r.getRequestedAt(), r.getExpiresAt());
    }
}
