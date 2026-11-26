package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Result of a provider decision. No customer address: the provider sees the full address only
 * from CONFIRMED (C-08).
 */
public record BookingDecisionResponse(
        String id,
        String ref,
        BookingStatus status,
        Instant acceptedAt,
        Instant paymentDueAt
) {
}
