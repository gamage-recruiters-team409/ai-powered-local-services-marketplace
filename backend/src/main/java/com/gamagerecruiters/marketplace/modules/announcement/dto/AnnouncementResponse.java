package com.gamagerecruiters.marketplace.modules.announcement.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;
import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;

/**
 * Admin view of an announcement. {@code message} is raw markdown (render it as markdown, never as HTML).
 * {@code pinActive} = published, pinned and pinExpiresAt still in the future.
 */
public record AnnouncementResponse(
        String id,
        String title,
        String message,
        AnnouncementAudience audience,
        AnnouncementStatus status,
        Instant scheduledAt,
        Instant publishedAt,
        boolean sendPush,
        boolean pinToHeader,
        Instant pinExpiresAt,
        boolean pinActive,
        DeliveryState deliveryState,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
