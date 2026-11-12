package com.gamagerecruiters.marketplace.modules.admin.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /admin/accounts. Only the fields UserInternalService.createAdminAccount needs; unknown JSON
 * fields are ignored, so nothing else (status, id, mustChangePassword, actor) can be set by the caller.
 *
 * <p>The temporary password only has {@code @NotBlank} here. Its policy (same regex as the auth module's
 * RegisterRequest/ChangePasswordRequest, max 64 characters, max 72 bytes for BCrypt) is checked in
 * AdminAccountService on purpose: a failed Bean Validation constraint puts the rejected value into
 * MethodArgumentNotValidException, which Spring logs at DEBUG/TRACE, and that value would be the password.</p>
 *
 * <p>{@link #toString()} never includes the password.</p>
 */
public record CreateAdminAccountRequest(
        @NotBlank(message = "fullName is required")
        @Size(max = 100, message = "fullName must not exceed 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}<>]*$", message = "fullName must not contain control characters, < or >")
        String fullName,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 254, message = "email must not exceed 254 characters")
        String email,

        @NotNull(message = "role is required")
        Role role,

        @NotBlank(message = "temporaryPassword is required")
        String temporaryPassword
) {

    @Override
    public String toString() {
        return "CreateAdminAccountRequest[fullName=" + fullName + ", email=" + email + ", role=" + role
                + ", temporaryPassword=***]";
    }
}
