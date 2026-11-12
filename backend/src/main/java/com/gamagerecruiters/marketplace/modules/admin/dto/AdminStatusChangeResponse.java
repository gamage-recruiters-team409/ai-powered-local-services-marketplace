package com.gamagerecruiters.marketplace.modules.admin.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/** Result of a status change. Contains no personal data beyond the user id. */
public record AdminStatusChangeResponse(
        String userId,
        Role role,
        UserStatus previousStatus,
        UserStatus status,
        String auditLogId
) {
}
