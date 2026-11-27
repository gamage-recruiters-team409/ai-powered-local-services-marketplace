package com.gamagerecruiters.marketplace.modules.auth.dto;

import com.gamagerecruiters.marketplace.common.validation.MaxUtf8Bytes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Token is required")
        @Size(max = 256, message = "Token must not exceed 256 characters")
        String token,

        @NotBlank(message = "Password is required")
        @MaxUtf8Bytes(72)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                message = "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String newPassword
) {
}
