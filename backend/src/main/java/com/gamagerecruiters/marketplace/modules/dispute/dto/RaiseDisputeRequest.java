package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RaiseDisputeRequest(
        @NotBlank(message = "bookingId is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "bookingId has an invalid format")
        String bookingId,

        @NotNull(message = "category is required")
        DisputeCategory category,

        @NotBlank(message = "description is required")
        @Size(max = DisputeRules.MAX_DESCRIPTION_LENGTH, message = "description must be at most 2000 characters")
        String description,

        @Size(max = DisputeRules.MAX_EVIDENCE_ITEMS, message = "at most 10 evidence references are allowed")
        List<@NotBlank(message = "evidence reference must not be blank")
             @Size(max = DisputeRules.MAX_EVIDENCE_REFERENCE_LENGTH,
                   message = "evidence reference must be at most 500 characters") String> evidence
) {
}
