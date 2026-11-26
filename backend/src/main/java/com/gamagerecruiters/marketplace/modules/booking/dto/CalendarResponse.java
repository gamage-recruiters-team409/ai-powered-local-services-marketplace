package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Provider booking calendar (FR-SCHD-005) for a date range: one entry per day (working hours, day off or blocked)
 * and the bookings in it. Local dates and times are Asia/Colombo. No customer address or notes.
 */
public record CalendarResponse(
        String from,
        String to,
        String timeZone,
        List<Day> days,
        List<Event> events
) {

    /** {@code working=false} with {@code blocked=false} means a day off. */
    public record Day(
            String date,
            String dayOfWeek,
            boolean working,
            String startTime,
            String endTime,
            boolean blocked,
            String blockedReason
    ) {
    }

    public record Event(
            String bookingId,
            String ref,
            BookingStatus status,
            String serviceTitle,
            String customerId,
            Instant slotStart,
            Instant slotEnd,
            String localDate,
            String localStart,
            String localEnd
    ) {
    }
}
