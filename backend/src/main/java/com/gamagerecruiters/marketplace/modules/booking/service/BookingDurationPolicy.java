package com.gamagerecruiters.marketplace.modules.booking.service;

/**
 * One duration contract for slots and booking creation.
 * <p>
 * WP03 allows any listing/add-on minutes (for example 60 + 10 = 70). Booking time is blocked on a
 * 15-minute grid, so the service minutes are rounded UP to the next 15 (70 → 75) for slots and the calendar.
 * The real service minutes stay in the price snapshot. One booking is at most one working day (480 minutes
 * after rounding); longer combinations are rejected before a booking is created.
 */
public final class BookingDurationPolicy {

    public static final int GRID_MINUTES = 15;
    public static final int MIN_SCHEDULED_MINUTES = 15;
    public static final int MAX_SCHEDULED_MINUTES = 480;

    private BookingDurationPolicy() {
    }

    /** Service minutes → minutes blocked in the calendar (rounded up to 15, at least 15). */
    public static int toScheduledMinutes(int serviceMinutes) {
        if (serviceMinutes <= 0) {
            throw new IllegalArgumentException("serviceMinutes must be positive");
        }
        int rounded = ((serviceMinutes + GRID_MINUTES - 1) / GRID_MINUTES) * GRID_MINUTES;
        return Math.max(rounded, MIN_SCHEDULED_MINUTES);
    }

    /** True when the service fits in one booking (1 to 480 minutes after rounding). */
    public static boolean isSchedulable(int serviceMinutes) {
        return serviceMinutes > 0 && toScheduledMinutes(serviceMinutes) <= MAX_SCHEDULED_MINUTES;
    }
}
