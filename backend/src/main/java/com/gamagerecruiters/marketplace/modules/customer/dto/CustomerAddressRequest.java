package com.gamagerecruiters.marketplace.modules.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerAddressRequest(
        @NotBlank(message = "Label is required")
        @Size(min = 1, max = 30, message = "Label must be between 1 and 30 characters")
        String label,

        @NotBlank(message = "Address line 1 is required")
        @Size(min = 3, max = 120, message = "Address line 1 must be between 3 and 120 characters")
        String addressLine1,

        @Size(max = 120, message = "Address line 2 must not exceed 120 characters")
        String addressLine2,

        @NotBlank(message = "City is required")
        @Size(min = 2, max = 60, message = "City must be between 2 and 60 characters")
        String city,

        @NotBlank(message = "District is required")
        String district,

        @Pattern(regexp = "^$|^[0-9]{5}$", message = "Postal code must be a 5-digit number")
        String postalCode,

        Boolean isDefault
) {
}
