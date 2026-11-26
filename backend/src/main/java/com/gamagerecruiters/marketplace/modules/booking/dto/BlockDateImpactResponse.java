package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * What blocking a date would affect (Block a date screen, right panel).
 * <ul>
 *   <li>{@code confirmedBookings}: need a decision each (Keep / Request new time).</li>
 *   <li>{@code pendingRequests}: will be declined automatically (no penalty).</li>
 *   <li>{@code awaitingPayment}: a payment may be in progress, so the date cannot be blocked until it
 *       finishes ({@code canBlock = false}).</li>
 * </ul>
 */
public record BlockDateImpactResponse(
        String date,
        List<Item> confirmedBookings,
        List<Item> pendingRequests,
        List<Item> awaitingPayment,
        boolean canBlock
) {

    /** No address or notes; {@code canRequestNewTime} applies the reschedule rules. */
    public record Item(
            String bookingId,
            String ref,
            BookingStatus status,
            String serviceTitle,
            String customerId,
            Instant slotStart,
            Instant slotEnd,
            String localStart,
            String localEnd,
            boolean reschedulePending,
            boolean canRequestNewTime
    ) {
    }
}
