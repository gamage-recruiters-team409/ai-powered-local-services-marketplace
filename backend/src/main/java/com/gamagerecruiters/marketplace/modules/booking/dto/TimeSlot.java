package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

/**
 * One bookable time slot. {@code start}/{@code end} are UTC instants (stored and sent to the
 * booking request); {@code localStart}/{@code localEnd} are "HH:mm" in Asia/Colombo for display.
 */
public record TimeSlot(
        Instant start,
        Instant end,
        String localStart,
        String localEnd
) {
}
