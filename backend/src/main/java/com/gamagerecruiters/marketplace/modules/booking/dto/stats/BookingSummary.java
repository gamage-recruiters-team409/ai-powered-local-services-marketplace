package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/** Short booking row for admin monitoring (WP09). No address or notes. */
public record BookingSummary(
        String bookingId,
        String ref,
        BookingStatus status,
        String customerId,
        String providerId,
        String listingId,
        String serviceTitle,
        Instant slotStart,
        Instant slotEnd
) {
}
