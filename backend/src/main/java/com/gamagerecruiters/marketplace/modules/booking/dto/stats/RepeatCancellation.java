package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.Instant;

/**
 * A user who cancelled {@code count} bookings in the window. {@code role}: CUSTOMER or PROVIDER.
 * {@code totalBookings}: bookings this user had in the same role during the window – created in the window, or
 * cancelled in the window (so every counted cancellation is included and {@code count <= totalBookings}).
 * Cancellation rate = count / totalBookings.
 */
public record RepeatCancellation(String userId, String role, long count, long totalBookings,
                                 Instant lastCancelledAt) {
}
