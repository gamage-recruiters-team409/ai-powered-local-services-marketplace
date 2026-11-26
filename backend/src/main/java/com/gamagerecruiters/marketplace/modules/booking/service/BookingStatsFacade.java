package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingSummary;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingUserStats;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.DailyCount;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.RepeatCancellation;

/**
 * Read-only booking numbers for the admin module (WP09). Changes nothing.
 * Callers: a logged-in admin (ADMIN, SUPER_ADMIN, SUPPORT_ADMIN, FINANCE_ADMIN, MODERATOR),
 * or a background job that runs outside an HTTP request. Others get 403 / 401.
 * Days and months use Asia/Colombo.
 */
public interface BookingStatsFacade {

    /** Totals, last 6 months and next 5 upcoming bookings of a user (as customer or provider). */
    BookingUserStats getUserStats(String userId);

    /**
     * CONFIRMED or IN_PROGRESS bookings whose slot already ended (provider did not mark done),
     * oldest first. {@code limit} 1–200.
     */
    List<BookingSummary> findOverdueBookings(int limit);

    /**
     * Users who cancelled at least {@code minCount} bookings in the last {@code days} days,
     * most cancellations first. {@code minCount} 1–100, {@code days} 1–365.
     */
    List<RepeatCancellation> findRepeatCancellations(int minCount, int days);

    /** Number of bookings in each status (every status is present, 0 if none). */
    Map<BookingStatus, Long> countByStatus();

    /** Bookings created per day from {@code from} to {@code to} inclusive (max 366 days, zero days included). */
    List<DailyCount> countCreatedPerDay(LocalDate from, LocalDate to);
}
