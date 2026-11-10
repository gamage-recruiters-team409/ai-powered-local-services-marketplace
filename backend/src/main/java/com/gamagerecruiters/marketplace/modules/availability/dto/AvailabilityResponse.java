package com.gamagerecruiters.marketplace.modules.availability.dto;

import java.time.Instant;
import java.util.List;

public record AvailabilityResponse(
        String providerId,
        List<WeeklyHoursResponse> weeklyHours,
        int bufferMinutes,
        Instant updatedAt
) {
}
