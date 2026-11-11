package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * What the dispute module needs to change in the Booking module (WP05).
 *
 * <p>TODO(WP05): implement with a Booking module service method and remove
 * {@code StubBookingDisputeStatusAdapter}.</p>
 */
public interface BookingDisputeStatusPort {

    /** Sets the booking to DISPUTED because the given dispute was raised. Must be idempotent. */
    void markDisputed(String bookingId, String disputeId);
}
