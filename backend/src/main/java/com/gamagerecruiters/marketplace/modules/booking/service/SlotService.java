package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;

public interface SlotService {

    /**
     * Returns the bookable slots for a provider on one date (Asia/Colombo).
     * Parameters come as raw strings so invalid values return a clear 400 instead of a binding error.
     */
    AvailableSlotsResponse getAvailableSlots(String providerId, String date, String durationMinutes);
}
