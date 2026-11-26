package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.LocalDate;

/** Bookings created on one day (Asia/Colombo). */
public record DailyCount(LocalDate date, long count) {
}
