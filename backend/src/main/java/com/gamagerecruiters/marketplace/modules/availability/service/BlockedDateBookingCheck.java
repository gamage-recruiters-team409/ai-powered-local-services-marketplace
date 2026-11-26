package com.gamagerecruiters.marketplace.modules.availability.service;

import java.time.LocalDate;

/**
 * Implemented by the booking module, so availability does not depend on booking (no module cycle).
 * Called before a blocked date is saved.
 */
public interface BlockedDateBookingCheck {

    /**
     * Throws 409 BOOKINGS_ON_DATE when the provider still has bookings on this date that need a decision
     * (pending requests, bookings awaiting payment, confirmed bookings not handled by the block-date flow).
     */
    void assertNoBookingsToHandle(String providerId, LocalDate date);
}
