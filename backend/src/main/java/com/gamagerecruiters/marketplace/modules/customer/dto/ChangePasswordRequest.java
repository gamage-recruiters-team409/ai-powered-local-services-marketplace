package com.gamagerecruiters.marketplace.modules.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 72, message = "Current password must not exceed 72 characters")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(max = 72, message = "Password must not exceed 72 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                message = "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String newPassword
) {
}
