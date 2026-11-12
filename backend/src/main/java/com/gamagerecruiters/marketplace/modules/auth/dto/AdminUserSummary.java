package com.gamagerecruiters.marketplace.modules.auth.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Administrative summary DTO representing a user account.
 * Exposes non-sensitive metadata for admin user search and management.
 * NEVER exposes password hashes, token data, or internal security counters.
 */
public record AdminUserSummary(
        String id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        Instant createdAt
) {
    public static AdminUserSummary fromUser(User user) {
        return new AdminUserSummary(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
