package com.gamagerecruiters.marketplace.modules.review.dto;

import com.gamagerecruiters.marketplace.modules.review.domain.ReportReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** S10 – report a published review. */
public record ReportReviewRequest(
        @NotNull(message = "Please choose a reason")
        ReportReason reason,

        @Size(max = 500, message = "Details must not exceed 500 characters")
        String details
) {
}
