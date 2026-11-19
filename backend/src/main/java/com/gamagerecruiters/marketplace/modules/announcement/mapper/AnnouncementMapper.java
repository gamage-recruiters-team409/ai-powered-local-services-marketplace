package com.gamagerecruiters.marketplace.modules.announcement.mapper;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.announcement.domain.Announcement;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementResponse;

public final class AnnouncementMapper {

    private AnnouncementMapper() {
    }

    public static AnnouncementResponse toResponse(Announcement a, Instant now) {
        return new AnnouncementResponse(
                a.getId(),
                a.getVersion(),
                a.getTitle(),
                a.getMessage(),
                a.getAudience(),
                a.getStatus(),
                a.getScheduledAt(),
                a.getPublishedAt(),
                a.isSendPush(),
                a.isPinToHeader(),
                a.getPinExpiresAt(),
                isPinActive(a, now),
                a.getDeliveryState(),
                a.getCreatedBy(),
                a.getUpdatedBy(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    /** Published, pinned to the header and the pin has not expired yet. */
    public static boolean isPinActive(Announcement a, Instant now) {
        return a.getStatus() == AnnouncementStatus.PUBLISHED && a.isPinToHeader()
                && a.getPinExpiresAt() != null && a.getPinExpiresAt().isAfter(now);
    }
}
