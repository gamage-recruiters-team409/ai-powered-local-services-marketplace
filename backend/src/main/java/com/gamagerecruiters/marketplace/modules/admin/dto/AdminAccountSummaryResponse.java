package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.util.Map;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Counts of admin-role accounts (SUPER_ADMIN, ADMIN, SUPPORT_ADMIN, MODERATOR, FINANCE_ADMIN).
 * Every admin role and every status is always present, with 0 when there are none.
 */
public record AdminAccountSummaryResponse(
        long total,
        Map<Role, Long> byRole,
        Map<UserStatus, Long> byStatus
) {
}
