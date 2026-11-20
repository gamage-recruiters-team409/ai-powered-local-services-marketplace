package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Result of suspend / reactivate. {@code changed = false}: the account already had that status, nothing was changed
 * or audited (idempotent), and there is no {@code auditLogId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountActionResponse(
        String userId,
        Role role,
        UserStatus previousStatus,
        UserStatus status,
        boolean changed,
        String auditLogId
) {
}
