package com.gamagerecruiters.marketplace.modules.availability.dto;

import java.time.DayOfWeek;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record WeeklyHoursRequest(
        @NotNull(message = "dayOfWeek is required")
        DayOfWeek dayOfWeek,

        boolean enabled,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "startTime must be in HH:mm format")
        String startTime,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "endTime must be in HH:mm format")
        String endTime
) {
}
