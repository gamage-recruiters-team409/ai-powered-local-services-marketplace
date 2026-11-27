package com.gamagerecruiters.marketplace.modules.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeactivateAccountRequest(
        @NotBlank(message = "Password confirmation is required")
        @Size(max = 1000, message = "Password must not exceed 1000 characters") // Request-size guard against DoS abuse; not 72 bytes so legacy passwords remain verifiable
        String password
) {
}
