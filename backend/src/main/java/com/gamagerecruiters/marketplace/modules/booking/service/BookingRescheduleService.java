package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleResponse;

/**
 * Reschedule (FR-BOOK-007): either party of a CONFIRMED booking proposes a new time; the other approves or
 * rejects; the requester can withdraw. Rules come from {@link ReschedulePolicy}.
 */
public interface BookingRescheduleService {

    /** Free slots for this booking's provider and length on a date, ignoring the booking's own current time. */
    AvailableSlotsResponse availableSlots(String bookingId, String date);

    RescheduleResponse request(String bookingId, RescheduleBookingRequest request);

    RescheduleResponse approve(String bookingId);

    RescheduleResponse reject(String bookingId);

    RescheduleResponse withdraw(String bookingId);
}
