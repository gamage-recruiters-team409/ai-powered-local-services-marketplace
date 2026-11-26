package com.gamagerecruiters.marketplace.modules.availability.service;

import java.time.LocalDate;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;

/**
 * The only entry point other modules (booking) use to read provider availability.
 * Other modules must not use the availability repositories directly.
 */
public interface AvailabilityFacade {

    /**
     * Returns the provider's weekly hours and buffer. A provider without a saved schedule
     * gets the default: all days off, 30 minutes buffer.
     */
    ProviderSchedule getSchedule(String providerId);

    /** Returns true if the provider has blocked this whole day. */
    boolean isDateBlocked(String providerId, LocalDate date);

    /**
     * Blocks a date for the provider with the same rules as POST /availability/me/blocked-dates
     * (used by the booking module's "block a date with bookings" flow after it has handled the bookings).
     */
    BlockedDateResponse createBlockedDate(String providerId, CreateBlockedDateRequest request);

    /** The provider's blocked dates from {@code from} to {@code to} inclusive (booking calendar view). */
    List<BlockedDateResponse> getBlockedDates(String providerId, LocalDate from, LocalDate to);
}
