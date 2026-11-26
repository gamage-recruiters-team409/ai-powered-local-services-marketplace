package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/v1/bookings/{id}/reschedule: the proposed new start (UTC) and an optional short note. */
public record RescheduleBookingRequest(
        @NotNull Instant newSlotStart,
        @Size(max = 300) String note
) {
}
