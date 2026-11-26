package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/** One row of My Bookings (customer or provider). No address. Local times are Asia/Colombo. */
public record BookingListItem(
        String id,
        String ref,
        BookingStatus status,
        String listingId,
        String serviceTitle,
        String customerId,
        String providerId,
        Instant slotStart,
        Instant slotEnd,
        String localDate,
        String localStart,
        String localEnd,
        BigDecimal totalPrice,
        String currency,
        String district,
        Instant createdAt
) {
}
