package com.gamagerecruiters.marketplace.modules.auth.dto;

import com.gamagerecruiters.marketplace.common.validation.MaxUtf8Bytes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 1000, message = "Current password must not exceed 1000 characters") // Request-size guard against DoS abuse; not 72 bytes so legacy passwords remain verifiable
        String currentPassword,

        @NotBlank(message = "New password is required")
        @MaxUtf8Bytes(72)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                message = "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String newPassword
) {
}
