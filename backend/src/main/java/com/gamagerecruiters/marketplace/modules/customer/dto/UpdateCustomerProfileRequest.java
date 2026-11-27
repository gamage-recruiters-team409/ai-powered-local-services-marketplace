package com.gamagerecruiters.marketplace.modules.customer.dto;

import com.gamagerecruiters.marketplace.common.util.ValidationConstants;
import com.gamagerecruiters.marketplace.common.util.ValidationUtils;
import com.gamagerecruiters.marketplace.common.validation.ValidFullName;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateCustomerProfileRequest(
        @NotBlank(message = "Full name is required")
        @ValidFullName
        String fullName,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = ValidationConstants.SRI_LANKAN_PHONE_REGEX, message = ValidationConstants.SRI_LANKAN_PHONE_MESSAGE)
        String phone
) {
    public UpdateCustomerProfileRequest {
        if (fullName != null) {
            fullName = ValidationUtils.normalizeFullName(fullName);
        }
        if (phone != null) {
            phone = ValidationUtils.stripSpaces(phone).replaceAll("[\\s\\-\\(\\)]", "");
        }
    }
}
