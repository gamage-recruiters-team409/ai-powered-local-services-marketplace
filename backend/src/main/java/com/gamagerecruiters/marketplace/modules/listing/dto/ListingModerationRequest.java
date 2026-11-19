package com.gamagerecruiters.marketplace.modules.listing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ListingModerationRequest(
        @NotNull(message = "Moderation decision is required")
        ModerationDecision decision,

        @Size(max = 500, message = "Moderation reason must not exceed 500 characters")
        String reason
) {
}
