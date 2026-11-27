package com.gamagerecruiters.marketplace.modules.availability.service;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Implemented by the booking module, so availability does not depend on booking (no module cycle).
 * Wraps the saving of a blocked date.
 */
public interface BlockedDateBookingCheck {

    /**
     * Saves the blocked date ({@code createBlockedDate}) only if the provider has no bookings on that date that
     * still need a decision; otherwise throws 409 BOOKINGS_ON_DATE and saves nothing.
     * <p>
     * The check and the save run in one MongoDB transaction that claims the provider's booking guard, the same
     * serialisation point booking creation uses, so a booking cannot be created on the date while it is being
     * blocked (and vice versa).
     */
    <T> T createIfNoBookingsToHandle(String providerId, LocalDate date, Supplier<T> createBlockedDate);
}
