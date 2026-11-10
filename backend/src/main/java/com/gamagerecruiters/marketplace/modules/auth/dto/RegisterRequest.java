package com.gamagerecruiters.marketplace.modules.auth.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Phone must be valid digits between 7 and 15 characters, optionally starting with '+'")
        String phone,

        @NotBlank(message = "Password is required")
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
        String policyVersion
) {
}
