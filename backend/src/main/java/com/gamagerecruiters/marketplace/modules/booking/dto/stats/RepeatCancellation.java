package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.Instant;

/** A user who cancelled {@code count} bookings in the window. {@code role}: CUSTOMER or PROVIDER. */
public record RepeatCancellation(String userId, String role, long count, Instant lastCancelledAt) {
}
