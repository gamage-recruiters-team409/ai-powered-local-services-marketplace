package com.gamagerecruiters.marketplace.modules.auth.dto;

import java.time.Instant;

/**
 * Represents a count of users registered within a time period.
 *
 * @param periodStart start of the aggregation period (inclusive)
 * @param count       number of new users registered in that period
 */
public record PeriodCount(Instant periodStart, long count) {
}
