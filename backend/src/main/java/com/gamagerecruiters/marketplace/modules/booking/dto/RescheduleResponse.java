package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/** Result of a reschedule action: the booking's current slot and the request it touched. */
public record RescheduleResponse(
        String bookingId,
        String ref,
        BookingStatus status,
        Instant slotStart,
        Instant slotEnd,
        RescheduleView reschedule,
        int remainingReschedules
) {
}
