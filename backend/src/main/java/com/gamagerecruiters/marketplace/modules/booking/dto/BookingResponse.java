package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Booking as shown to its customer (full address). Times are UTC; {@code localDate},
 * {@code localStart} and {@code localEnd} are Asia/Colombo for display.
 * {@code serviceTitle}, {@code totalPrice} and {@code currency} come from the price snapshot.
 */
public record BookingResponse(
        String id,
        String ref,
        BookingStatus status,
        String customerId,
        String providerId,
        String listingId,
        Instant slotStart,
        Instant slotEnd,
        String localDate,
        String localStart,
        String localEnd,
        int durationMinutes,
        BookingAddressRequest address,
        String notes,
        Instant responseDeadline,
        Instant createdAt,
        String serviceTitle,
        BigDecimal totalPrice,
        String currency
) {
}
