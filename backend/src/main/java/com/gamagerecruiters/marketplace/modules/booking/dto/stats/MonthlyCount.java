package com.gamagerecruiters.marketplace.modules.booking.dto.stats;

import java.time.YearMonth;

/** Bookings created in one month (Asia/Colombo). */
public record MonthlyCount(YearMonth month, long count) {
}
