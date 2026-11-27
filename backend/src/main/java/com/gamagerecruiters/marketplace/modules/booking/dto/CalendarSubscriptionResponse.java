package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

/**
 * The private calendar feed link. {@code url} and {@code webcalUrl} are returned only right after the link is
 * created or reset (only a hash of the secret is stored); afterwards {@code active} shows whether one exists.
 */
public record CalendarSubscriptionResponse(
        boolean active,
        String url,
        String webcalUrl,
        Instant createdAt,
        Instant lastUsedAt
) {
}
