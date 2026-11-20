package com.gamagerecruiters.marketplace.modules.listing.dto;

import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateListingStatusRequest(
        @NotNull(message = "Target listing status is required")
        ListingStatus status,

        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {
}
