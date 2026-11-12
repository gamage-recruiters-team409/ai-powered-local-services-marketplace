package com.gamagerecruiters.marketplace.modules.availability.service;

import java.time.LocalDate;

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
}
