package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Minimal booking information shared with other modules (for example messaging, completion and reviews).
 * Contains IDs, status and the booked time slot only, no address or price data.
 * {@code slotStart} and {@code slotEnd} are in UTC.
 */
public record BookingParticipants(
        String bookingId,
        String seriesId,
        String listingId,
        String customerId,
        String providerId,
        BookingStatus status,
        Instant slotStart,
        Instant slotEnd
) {
}
