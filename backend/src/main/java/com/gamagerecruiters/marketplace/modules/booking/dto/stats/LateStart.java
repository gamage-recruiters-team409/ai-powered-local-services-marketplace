package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.Instant;

/**
 * A CONFIRMED booking whose scheduled start has passed but the provider has not started it (WP09 Late cases).
 * {@code minutesLate} = whole minutes from {@code slotStart} to now. No address or notes.
 */
public record LateStart(
        String bookingId,
        String ref,
        String listingId,
        String serviceTitle,
        String categoryId,
        String customerId,
        String providerId,
        Instant slotStart,
        long minutesLate
) {
}
