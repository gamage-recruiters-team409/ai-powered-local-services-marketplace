package com.gamagerecruiters.marketplace.modules.availability.dto;

import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBlockedDateRequest(
        @NotBlank(message = "date is required")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "date must be in yyyy-MM-dd format")
        String date,

        @NotNull(message = "reason is required")
        BlockReason reason,

        @Size(max = 200, message = "note must be at most 200 characters")
        String note
) {
}
