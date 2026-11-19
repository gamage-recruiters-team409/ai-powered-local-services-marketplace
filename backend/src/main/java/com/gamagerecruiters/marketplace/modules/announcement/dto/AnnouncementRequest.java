package com.gamagerecruiters.marketplace.modules.announcement.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementRules;
import com.gamagerecruiters.marketplace.modules.announcement.validation.NoHtmlTags;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Create/update body. {@code message} is markdown, stored raw; raw HTML tags are rejected. {@code scheduledAt} is
 * UTC (ISO-8601). With intent PUBLISH, a scheduledAt more than 1 minute ahead schedules it, otherwise it is published
 * now. {@code sendPush} and {@code pinToHeader} default to false.
 */
public record AnnouncementRequest(
        @NotBlank(message = "title is required")
        @Size(max = AnnouncementRules.MAX_TITLE_LENGTH, message = "title must be at most 80 characters")
        @NoHtmlTags(message = "title must not contain HTML tags")
        String title,

        @NotBlank(message = "message is required")
        @Size(max = AnnouncementRules.MAX_MESSAGE_LENGTH, message = "message must be at most 5000 characters")
        @NoHtmlTags(message = "message is markdown and must not contain HTML tags")
        String message,

        @NotNull(message = "audience is required")
        AnnouncementAudience audience,

        Instant scheduledAt,

        Boolean sendPush,

        Boolean pinToHeader,

        @NotNull(message = "intent is required")
        AnnouncementIntent intent,

        /*
         * The version of the announcement the admin loaded (AnnouncementResponse.version). Required on update: if the
         * announcement changed since then the update is rejected with 409 ANNOUNCEMENT_UPDATE_CONFLICT instead of
         * overwriting the newer change. Ignored on create.
         */
        @PositiveOrZero(message = "version must be 0 or more")
        Long version
) {

    public boolean push() {
        return Boolean.TRUE.equals(sendPush);
    }

    public boolean pinned() {
        return Boolean.TRUE.equals(pinToHeader);
    }
}
