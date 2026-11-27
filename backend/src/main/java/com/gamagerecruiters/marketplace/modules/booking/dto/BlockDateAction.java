package com.gamagerecruiters.marketplace.modules.booking.dto;

/**
 * What the provider does with a confirmed booking on a date they are blocking (Block a date screen).
 * "Cancel" is added with the cancellation feature (refund policy + provider penalty).
 */
public enum BlockDateAction {
    /** The booking stays; only new bookings on that day are stopped. */
    KEEP,
    /** The provider proposes a new time (a normal reschedule request the customer answers). */
    REQUEST_NEW_TIME
}
