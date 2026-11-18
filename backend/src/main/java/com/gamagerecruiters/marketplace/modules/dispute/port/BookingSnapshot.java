package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * The booking facts the dispute module needs, and nothing more.
 *
 * <p>Who may raise a dispute and when (booking status, the 72-hour window after completion, BR-008) is decided
 * by the Booking module when the booking is moved to DISPUTED, not from this snapshot.</p>
 *
 * @param bookingId       booking id
 * @param customerId      user id of the customer who booked
 * @param providerId      user id of the provider who delivers the booking
 * @param alreadyDisputed true when the booking is already DISPUTED (for example by the completion "Raise issue" flow)
 */
public record BookingSnapshot(String bookingId, String customerId, String providerId, boolean alreadyDisputed) {

    /** True if the user is the booking's customer or its provider. */
    public boolean isParty(String userId) {
        return userId != null && (userId.equals(customerId) || userId.equals(providerId));
    }
}
