package com.gamagerecruiters.marketplace.modules.availability.mapper;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;
import com.gamagerecruiters.marketplace.modules.availability.domain.WeeklyHours;
import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;

public final class AvailabilityMapper {

    private AvailabilityMapper() {
    }

    public static AvailabilityResponse toResponse(AvailabilityRule rule) {
        List<WeeklyHoursResponse> days = rule.getWeeklyHours().stream()
                .map(AvailabilityMapper::toResponse)
                .toList();
        return new AvailabilityResponse(rule.getProviderId(), days, rule.getBufferMinutes(), rule.getUpdatedAt());
    }

    public static WeeklyHoursResponse toResponse(WeeklyHours hours) {
        return new WeeklyHoursResponse(hours.getDayOfWeek(), hours.isEnabled(), hours.getStartTime(), hours.getEndTime());
    }

    public static WeeklyHours toDomain(WeeklyHoursRequest request) {
        return new WeeklyHours(request.dayOfWeek(), request.enabled(), request.startTime(), request.endTime());
    }

    public static BlockedDateResponse toResponse(BlockedDate blockedDate) {
        return new BlockedDateResponse(
                blockedDate.getId(),
                blockedDate.getDate(),
                blockedDate.getReason(),
                blockedDate.getNote(),
                blockedDate.getCreatedAt());
    }
}
