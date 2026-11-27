package com.gamagerecruiters.marketplace.modules.auth.dto;

import com.gamagerecruiters.marketplace.common.util.ValidationConstants;
import com.gamagerecruiters.marketplace.common.util.ValidationUtils;
import com.gamagerecruiters.marketplace.common.validation.MaxUtf8Bytes;
import com.gamagerecruiters.marketplace.common.validation.ValidFullName;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Name is required")
        @ValidFullName
        String name,

        @NotBlank(message = "Email is required")
        @Size(max = 254, message = "Email must not exceed 254 characters")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = ValidationConstants.SRI_LANKAN_PHONE_REGEX, message = ValidationConstants.SRI_LANKAN_PHONE_MESSAGE)
        String phone,

        @NotBlank(message = "Password is required")
        @MaxUtf8Bytes(72)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                message = "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one digit"
        )
        String password,

        @NotNull(message = "Role is required")
        Role role,

        @NotNull(message = "Terms acceptance status is required")
        @AssertTrue(message = "Terms and conditions must be accepted")
        Boolean acceptedTerms,

        @NotBlank(message = "Policy version is required")
        @Size(max = 20, message = "Policy version must not exceed 20 characters")
        String policyVersion
) {
    public RegisterRequest {
        if (name != null) {
            name = ValidationUtils.normalizeFullName(name);
        }
        if (email != null) {
            email = ValidationUtils.stripSpaces(email).toLowerCase(java.util.Locale.ROOT);
        }
        if (phone != null) {
            phone = ValidationUtils.stripSpaces(phone).replaceAll("[\\s\\-\\(\\)]", "");
        }
    }
}
