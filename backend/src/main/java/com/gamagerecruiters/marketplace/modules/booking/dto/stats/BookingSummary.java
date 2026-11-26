package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/** Short booking row for admin monitoring (WP09). No address or notes. {@code categoryId} is from the price snapshot. */
public record BookingSummary(
        String bookingId,
        String ref,
        BookingStatus status,
        String customerId,
        String providerId,
        String listingId,
        String serviceTitle,
        String categoryId,
        Instant slotStart,
        Instant slotEnd
) {
}
