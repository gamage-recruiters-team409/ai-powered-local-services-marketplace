package com.gamagerecruiters.marketplace.modules.availability.dto;

import java.time.DayOfWeek;

public record WeeklyHoursResponse(
        DayOfWeek dayOfWeek,
        boolean enabled,
        String startTime,
        String endTime
) {
}
