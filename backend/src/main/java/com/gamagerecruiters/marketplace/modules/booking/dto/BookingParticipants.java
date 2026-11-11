package com.gamagerecruiters.marketplace.modules.booking.dto;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Minimal booking information shared with other modules (for example messaging and reviews).
 * Contains IDs and status only, no address or price data.
 */
public record BookingParticipants(
        String bookingId,
        String seriesId,
        String listingId,
        String customerId,
        String providerId,
        BookingStatus status
) {
}
