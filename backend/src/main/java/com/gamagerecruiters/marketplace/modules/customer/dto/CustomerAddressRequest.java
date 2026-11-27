package com.gamagerecruiters.marketplace.modules.customer.dto;

import com.gamagerecruiters.marketplace.common.util.ValidationConstants;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerAddressRequest(
        @NotBlank(message = "Label is required")
        @Size(min = 1, max = 30, message = "Label must be between 1 and 30 characters")
        @Pattern(regexp = ValidationConstants.NO_CONTROL_CHARS_REGEX, message = ValidationConstants.NO_CONTROL_CHARS_MESSAGE)
        String label,

        @NotBlank(message = "Address line 1 is required")
        @Size(min = 3, max = 120, message = "Address line 1 must be between 3 and 120 characters")
        @Pattern(regexp = ValidationConstants.NO_CONTROL_CHARS_REGEX, message = ValidationConstants.NO_CONTROL_CHARS_MESSAGE)
        String addressLine1,

        @Size(max = 120, message = "Address line 2 must not exceed 120 characters")
        @Pattern(regexp = "^$|^[^\\p{Cntrl}]+$", message = "Address line 2 must not contain control characters")
        String addressLine2,

        @NotBlank(message = "City is required")
        @Size(min = 2, max = 60, message = "City must be between 2 and 60 characters")
        @Pattern(regexp = ValidationConstants.CITY_REGEX, message = ValidationConstants.CITY_MESSAGE)
        String city,

        @NotBlank(message = "District is required")
        @Size(max = 50, message = "District must not exceed 50 characters")
        String district,

        @Pattern(regexp = "^$|^[0-9]{5}$", message = "Postal code must be a 5-digit number")
        String postalCode,

        Boolean isDefault
) {
    public CustomerAddressRequest {
        if (label != null) {
            label = label.trim();
        }
        if (addressLine1 != null) {
            addressLine1 = addressLine1.trim();
        }
        if (addressLine2 != null) {
            addressLine2 = addressLine2.trim();
        }
        if (city != null) {
            city = city.trim();
        }
        if (district != null) {
            district = district.trim();
        }
        if (postalCode != null) {
            postalCode = postalCode.trim();
        }
    }
}
