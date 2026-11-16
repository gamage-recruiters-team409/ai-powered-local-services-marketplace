package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;

/**
 * An admin account as returned by the admin module. Never contains a password, hash, token or security counter.
 * {@code mustChangePassword} is only present when the auth result exposes it (create); the list returns
 * auth's AdminUserSummary, which does not, so the field is left out there.
 */
public record AdminAccountResponse(
        String id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean mustChangePassword
) {

    public static AdminAccountResponse from(AdminUserSummary summary) {
        return new AdminAccountResponse(summary.id(), summary.fullName(), summary.email(), summary.phone(),
                summary.role(), summary.status(), summary.createdAt(), null);
    }

    /** Built through auth's own AdminUserSummary mapping, plus the mustChangePassword flag. */
    public static AdminAccountResponse fromCreated(User created) {
        AdminUserSummary summary = AdminUserSummary.fromUser(created);
        return new AdminAccountResponse(summary.id(), summary.fullName(), summary.email(), summary.phone(),
                summary.role(), summary.status(), summary.createdAt(), created.isMustChangePassword());
    }
}
