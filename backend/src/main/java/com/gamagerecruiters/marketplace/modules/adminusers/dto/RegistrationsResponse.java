package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * New customers and providers per bucket: the last 8 weeks (weeks start Monday 00:00 UTC) or the last 6 months
 * (months start on the 1st, 00:00 UTC), oldest first, the current (partial) bucket last. Buckets are [start, end).
 * When {@code available} is false there are no buckets and {@code reason} says why.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistrationsResponse(
        String period,
        boolean available,
        String reason,
        List<Bucket> buckets
) {

    public record Bucket(Instant start, Instant end, long customers, long providers) {
    }
}
