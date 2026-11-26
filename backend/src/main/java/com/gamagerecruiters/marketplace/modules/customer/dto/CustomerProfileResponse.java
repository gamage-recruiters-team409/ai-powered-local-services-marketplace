package com.gamagerecruiters.marketplace.modules.customer.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

public record CustomerProfileResponse(
        String id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        boolean emailVerified,
        String profileImageUrl
) {
    public CustomerProfileResponse(
            String id,
            String fullName,
            String email,
            String phone,
            Role role,
            UserStatus status,
            boolean emailVerified
    ) {
        this(id, fullName, email, phone, role, status, emailVerified, null);
    }
}
