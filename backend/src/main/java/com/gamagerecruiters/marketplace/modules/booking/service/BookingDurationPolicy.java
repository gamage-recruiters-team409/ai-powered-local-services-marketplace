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

    /**
     * Service minutes → minutes blocked in the calendar (rounded up to 15, at least 15).
     * Only for schedulable values (1–480): anything else is rejected BEFORE the arithmetic,
     * so a huge value (e.g. Integer.MAX_VALUE) can never overflow into a short booking.
     */
    public static int toScheduledMinutes(int serviceMinutes) {
        if (!isSchedulable(serviceMinutes)) {
            throw new IllegalArgumentException("serviceMinutes must be between 1 and " + MAX_SCHEDULED_MINUTES);
        }
        int rounded = ((serviceMinutes + GRID_MINUTES - 1) / GRID_MINUTES) * GRID_MINUTES;
        return Math.max(rounded, MIN_SCHEDULED_MINUTES);
    }

    /**
     * True when the service fits in one booking: 1 to 480 minutes. 480 is on the grid, so every value in
     * this range rounds up to at most 480. Range check only – no arithmetic, so no overflow.
     */
    public static boolean isSchedulable(int serviceMinutes) {
        return serviceMinutes >= 1 && serviceMinutes <= MAX_SCHEDULED_MINUTES;
    }
}
