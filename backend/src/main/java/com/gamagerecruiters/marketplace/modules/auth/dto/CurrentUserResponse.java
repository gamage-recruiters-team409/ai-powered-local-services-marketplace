package com.gamagerecruiters.marketplace.modules.auth.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

public record CurrentUserResponse(
        String id,
        String name,
        String email,
        String phone,
        Role role,
        UserStatus status,
        boolean emailVerified,
        Instant createdAt
) {
}
