package com.gamagerecruiters.marketplace.modules.review.dto;

import jakarta.validation.constraints.Size;

/** S16 – moderator publishes a held review or keeps a reported one: optional internal note. */
public record PublishReviewRequest(
        @Size(max = 500, message = "The note must not exceed 500 characters")
        String note
) {
}
