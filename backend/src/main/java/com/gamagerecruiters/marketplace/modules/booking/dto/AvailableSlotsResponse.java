package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.util.List;

/**
 * Available slots for one provider on one day.
 * {@code dayStatus}: WORKING, DAY_OFF (not a working day) or BLOCKED (provider blocked the date).
 */
public record AvailableSlotsResponse(
        String providerId,
        String date,
        String timeZone,
        int durationMinutes,
        int bufferMinutes,
        String dayStatus,
        List<TimeSlot> slots
) {
}
