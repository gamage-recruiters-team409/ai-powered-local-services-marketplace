package com.gamagerecruiters.marketplace.modules.review.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * S16 – moderator publishes a held review or keeps a reported one.
 *
 * @param note     optional internal note
 * @param revision the review's {@code moderationRevision} the moderator saw (from the queue or detail response);
 *                 required, so a decision based on an older state is rejected with 409
 */
public record PublishReviewRequest(
        @Size(max = 500, message = "The note must not exceed 500 characters")
        String note,

        @PositiveOrZero(message = "The revision must be 0 or more")
        Long revision
) {
}
