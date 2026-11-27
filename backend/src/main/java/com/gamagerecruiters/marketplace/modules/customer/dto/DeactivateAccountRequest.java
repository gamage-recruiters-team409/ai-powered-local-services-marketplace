package com.gamagerecruiters.marketplace.modules.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeactivateAccountRequest(
        @NotBlank(message = "Password confirmation is required")
        @Size(max = 72, message = "Password must not exceed 72 characters")
        String password
) {
}
