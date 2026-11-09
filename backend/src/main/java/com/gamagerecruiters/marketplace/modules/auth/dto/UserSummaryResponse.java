package com.gamagerecruiters.marketplace.modules.auth.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

public record UserSummaryResponse(
        String id,
        String name,
        String email,
        Role role,
        UserStatus status
) {
}
