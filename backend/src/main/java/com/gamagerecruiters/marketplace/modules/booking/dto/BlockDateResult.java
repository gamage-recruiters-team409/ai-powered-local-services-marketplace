package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;

/** Result of blocking a date with bookings. */
public record BlockDateResult(
        BlockedDateResponse blockedDate,
        List<String> declinedBookingIds,
        List<String> keptBookingIds,
        List<String> newTimeRequestedBookingIds
) {
}
