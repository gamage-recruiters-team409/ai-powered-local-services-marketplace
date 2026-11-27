package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.LocalDate;

/** A count for one Asia/Colombo day, used by per-day charts. */
public record DayCount(LocalDate date, long count) {
}
