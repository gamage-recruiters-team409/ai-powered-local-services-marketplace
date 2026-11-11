package com.gamagerecruiters.marketplace.modules.dispute.port;

import java.time.Instant;

/**
 * The booking facts the dispute module needs, and nothing more.
 *
 * @param bookingId  booking id
 * @param customerId user id of the customer who booked
 * @param providerId user id of the provider who delivers the booking
 * @param slotEndAt  end of the booked time slot; the 72h raise window starts here
 */
public record BookingSnapshot(String bookingId, String customerId, String providerId, Instant slotEndAt) {
}
