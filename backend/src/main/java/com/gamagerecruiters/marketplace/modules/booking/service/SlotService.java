package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;

public interface SlotService {

    /**
     * Returns the bookable slots for a provider on one date (Asia/Colombo).
     * Parameters come as raw strings so invalid values return a clear 400 instead of a binding error.
     */
    AvailableSlotsResponse getAvailableSlots(String providerId, String date, String durationMinutes);

    /**
     * Re-checks one chosen slot with the same rules as {@link #getAvailableSlots}: working hours,
     * blocked date, 3 h notice, 30-minute start grid and existing bookings plus buffer.
     */
    boolean isSlotAvailable(String providerId, Instant slotStart, int durationMinutes);

    /**
     * Same as {@link #getAvailableSlots(String, String, String)} but ignores one booking's own time
     * (used when that booking is being rescheduled). No access check: callers must check the booking.
     */
    AvailableSlotsResponse getAvailableSlots(String providerId, String date, String durationMinutes,
                                             String excludeBookingId);

    /** Same as {@link #isSlotAvailable(String, Instant, int)} but ignores one booking's own time. */
    boolean isSlotAvailable(String providerId, Instant slotStart, int durationMinutes, String excludeBookingId);
}
