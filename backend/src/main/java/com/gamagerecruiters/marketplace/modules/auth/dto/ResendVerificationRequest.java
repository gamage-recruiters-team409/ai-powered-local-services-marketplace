package com.gamagerecruiters.marketplace.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(
        @NotBlank(message = "Email is required")
        @Size(max = 254, message = "Email must not exceed 254 characters")
        @Email(message = "Invalid email format")
        String email
) {
    public ResendVerificationRequest {
        if (email != null) {
            email = email.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
