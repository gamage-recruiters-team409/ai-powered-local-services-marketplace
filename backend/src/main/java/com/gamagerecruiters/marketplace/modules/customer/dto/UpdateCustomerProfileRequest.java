package com.gamagerecruiters.marketplace.modules.customer.dto;

import com.gamagerecruiters.marketplace.common.util.ValidationConstants;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCustomerProfileRequest(
        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 100, message = "Full name must not exceed 100 characters")
        @Pattern(regexp = ValidationConstants.FULL_NAME_REGEX, message = ValidationConstants.FULL_NAME_MESSAGE)
        String fullName,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = ValidationConstants.SRI_LANKAN_PHONE_REGEX, message = ValidationConstants.SRI_LANKAN_PHONE_MESSAGE)
        String phone
) {
    public UpdateCustomerProfileRequest {
        if (fullName != null) {
            fullName = fullName.trim().replaceAll("\\s+", " ");
        }
        if (phone != null) {
            phone = phone.trim().replaceAll("[\\s\\-\\(\\)]", "");
        }
    }
}
