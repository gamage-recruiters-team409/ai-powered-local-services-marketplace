package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Trusted payment values for WP07 checkout, read from the booking (never from the frontend).
 *
 * @param amount          total price captured from the listing at booking creation (immutable)
 * @param currency        LKR
 * @param commissionRate  category commission rate captured at booking creation
 * @param priceCapturedAt when the price snapshot was taken
 * @param acceptedAt      when the provider accepted (null before acceptance)
 * @param paymentDueAt    payment deadline (acceptedAt + payment window; null before acceptance)
 */
public record BookingPaymentSnapshot(
        String bookingId,
        String bookingRef,
        String customerId,
        String providerId,
        String listingId,
        BookingStatus status,
        BigDecimal amount,
        String currency,
        BigDecimal commissionRate,
        Instant priceCapturedAt,
        Instant acceptedAt,
        Instant paymentDueAt
) {
}
