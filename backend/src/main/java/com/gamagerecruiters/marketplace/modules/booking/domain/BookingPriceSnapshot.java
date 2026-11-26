package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Immutable price, commission and duration captured from the listing when the booking was created
 * (FR-BOOK-003). Later listing or category changes never alter it; WP07 charges this amount.
 *
 * @param totalPrice     base price + selected add-ons: the full amount for the booked duration
 * @param currency       platform currency (LKR)
 * @param commissionRate category commission rate at booking time
 */
public record BookingPriceSnapshot(
        String serviceTitle,
        String categoryId,
        String pricingModel,
        BigDecimal basePrice,
        List<BookingAddOnSnapshot> addOns,
        BigDecimal totalPrice,
        String currency,
        BigDecimal commissionRate,
        int durationMinutes,
        Instant capturedAt
) {

    public static final String PLATFORM_CURRENCY = "LKR";

    public BookingPriceSnapshot {
        addOns = addOns == null ? List.of() : List.copyOf(addOns);
    }
}
