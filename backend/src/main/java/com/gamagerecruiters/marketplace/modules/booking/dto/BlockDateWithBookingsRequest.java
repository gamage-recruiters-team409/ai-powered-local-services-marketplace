package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Block a date that has bookings: the blocked-date fields plus one decision per confirmed booking. */
public record BlockDateWithBookingsRequest(
        @NotBlank(message = "date is required")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "date must be in yyyy-MM-dd format")
        String date,

        @NotNull(message = "reason is required")
        BlockReason reason,

        @Size(max = 200, message = "note must be at most 200 characters")
        String note,

        @Size(max = 50)
        List<@Valid BlockDateDecision> decisions
) {
}
