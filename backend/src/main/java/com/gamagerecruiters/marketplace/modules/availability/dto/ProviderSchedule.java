package com.gamagerecruiters.marketplace.modules.availability.dto;

import java.util.List;

/**
 * A provider's weekly working hours and buffer, shared with the booking module through
 * {@code AvailabilityFacade}. Times are "HH:mm" in the business timezone (Asia/Colombo).
 */
public record ProviderSchedule(
        String providerId,
        List<WeeklyHoursResponse> weeklyHours,
        int bufferMinutes
) {
}
