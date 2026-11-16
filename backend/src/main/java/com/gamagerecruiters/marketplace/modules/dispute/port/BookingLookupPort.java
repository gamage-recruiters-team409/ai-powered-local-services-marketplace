package com.gamagerecruiters.marketplace.modules.dispute.port;

/** What the dispute module reads from the Booking module (WP05), through its public BookingFacade. */
public interface BookingLookupPort {

    /**
     * Returns the booking's parties and whether it is already DISPUTED.
     *
     * <p>An unknown booking is reported by the Booking module itself (404 BOOKING_NOT_FOUND); that error is
     * not caught or changed here.</p>
     */
    BookingSnapshot getBooking(String bookingId);
}
