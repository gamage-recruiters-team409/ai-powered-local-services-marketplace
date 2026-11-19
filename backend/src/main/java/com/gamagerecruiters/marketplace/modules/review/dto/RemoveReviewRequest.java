package com.gamagerecruiters.marketplace.modules.review.dto;

import com.gamagerecruiters.marketplace.modules.review.domain.RemovalReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * S16 – moderator removes a review: a reason is required, a note is optional (internal).
 *
 * @param revision the review's {@code moderationRevision} the moderator saw (from the queue or detail response);
 *                 required, so a decision based on an older state is rejected with 409
 */
public record RemoveReviewRequest(
        @NotNull(message = "Please choose a reason")
        RemovalReason reasonCode,

        @Size(max = 500, message = "The note must not exceed 500 characters")
        String note,

        @PositiveOrZero(message = "The revision must be 0 or more")
        Long revision
) {
}
