package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.util.List;

/**
 * Booking numbers for one user (as customer or provider) for the admin user page.
 * {@code disputed} counts bookings that were ever DISPUTED, even if settled later.
 * {@code lastSixMonths} is oldest first and includes months with 0.
 */
public record BookingUserStats(
        String userId,
        long total,
        long completed,
        long cancelled,
        long disputed,
        List<MonthlyCount> lastSixMonths,
        List<BookingSummary> nextBookings
) {
}
