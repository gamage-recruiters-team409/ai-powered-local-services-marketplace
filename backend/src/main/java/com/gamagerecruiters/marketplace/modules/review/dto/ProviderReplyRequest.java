package com.gamagerecruiters.marketplace.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProviderReplyRequest(
        @NotBlank(message = "Reply text is required")
        @Size(max = 1000, message = "Reply must not exceed 1000 characters")
        String text
) {
}
