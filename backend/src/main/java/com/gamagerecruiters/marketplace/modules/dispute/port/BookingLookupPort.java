package com.gamagerecruiters.marketplace.modules.dispute.port;

import java.util.Optional;

/**
 * What the dispute module needs to read from the Booking module (WP05).
 *
 * <p>TODO(WP05): implement with the Booking module's service (never its repository) and remove
 * {@code StubBookingLookupAdapter}.</p>
 */
public interface BookingLookupPort {

    /** Returns the booking's parties and slot end, or empty when the booking does not exist. */
    Optional<BookingSnapshot> findBooking(String bookingId);
}
