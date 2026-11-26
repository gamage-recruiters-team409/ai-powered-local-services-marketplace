package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.math.BigDecimal;

/** One add-on selected at booking time, as priced then. */
public record BookingAddOnSnapshot(
        String addOnId,
        String title,
        BigDecimal extraPrice,
        int extraDurationMinutes
) {
}
