package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * The Booking module's (WP05) read-only check that the logged-in user may raise a dispute for a booking, used for
 * every raise. Backed by {@code BookingFacade.assertDisputeEligible(bookingId, actorId)}; changes nothing.
 *
 * <p>Eligible: CONFIRMED, IN_PROGRESS, COMPLETION_PENDING; COMPLETED within 72h of completion; already DISPUTED within
 * 72h of moving to DISPUTED. Errors are passed through unchanged: 401 not logged in; 403 ACCESS_DENIED for another
 * actor id or a non-participant; 404 BOOKING_NOT_FOUND; 409 INVALID_STATUS_TRANSITION; 409 DISPUTE_WINDOW_CLOSED.</p>
 */
public interface DisputeEligibilityPort {

    /** Returns the booking (parties and whether it is already DISPUTED) when eligible; throws the facade error otherwise. */
    BookingSnapshot assertEligible(String bookingId);
}
