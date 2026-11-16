package com.gamagerecruiters.marketplace.modules.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCustomerProfileRequest(
        @NotBlank(message = "Full name is required")
        @Size(min = 1, max = 100, message = "Full name must not exceed 100 characters")
        String fullName,

        @NotBlank(message = "Phone number is required")
        String phone
) {
}
