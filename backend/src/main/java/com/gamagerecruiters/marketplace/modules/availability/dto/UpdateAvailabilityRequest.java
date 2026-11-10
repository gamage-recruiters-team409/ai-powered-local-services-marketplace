package com.gamagerecruiters.marketplace.modules.availability.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateAvailabilityRequest(
        @NotNull(message = "weeklyHours is required")
        @Size(min = 7, max = 7, message = "weeklyHours must contain exactly 7 days")
        List<@Valid @NotNull WeeklyHoursRequest> weeklyHours,

        @NotNull(message = "bufferMinutes is required")
        @Min(value = 0, message = "bufferMinutes must be between 0 and 120")
        @Max(value = 120, message = "bufferMinutes must be between 0 and 120")
        Integer bufferMinutes
) {
}
