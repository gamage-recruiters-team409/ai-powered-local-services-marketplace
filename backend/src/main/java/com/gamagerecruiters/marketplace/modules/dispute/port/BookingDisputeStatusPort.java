package com.gamagerecruiters.marketplace.modules.dispute.port;

/** What the dispute module changes in the Booking module (WP05), through its public BookingFacade. */
public interface BookingDisputeStatusPort {

    /**
     * Moves the booking to DISPUTED, acting as the logged-in user.
     *
     * <p>The Booking module decides whether this is allowed (participant, starting status, the 72-hour window
     * after completion). Its errors (401, 403 ACCESS_DENIED, 404 BOOKING_NOT_FOUND, 409 INVALID_STATUS_TRANSITION,
     * DISPUTE_WINDOW_CLOSED, BOOKING_UPDATE_CONFLICT) are passed through unchanged.</p>
     *
     * @param reason short, system-generated text recorded in the booking timeline (never the user's description)
     */
    void markDisputed(String bookingId, String reason);
}
