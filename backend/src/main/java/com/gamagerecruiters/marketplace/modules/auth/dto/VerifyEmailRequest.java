package com.gamagerecruiters.marketplace.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank(message = "Token is required")
        @Size(max = 256, message = "Token must not exceed 256 characters")
        String token
) {
}
