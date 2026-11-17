package com.gamagerecruiters.marketplace.modules.verification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerificationDecisionRequest(

        @NotBlank(message = "Decision reason is required")

        @Size(
                max = 1000,
                message = "Decision reason must not exceed 1000 characters"
        )
        String reason

) {
}