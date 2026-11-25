package com.gamagerecruiters.marketplace.modules.adminusers.security;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

/**
 * {@code @PreAuthorize} expressions for the admin users endpoints: the same approved admin access policy as the
 * dispute and announcement admin endpoints (the ADMIN area). ADMIN reads and suspends / reactivates; SUPER_ADMIN
 * reads only (writes 403); every other role 403.
 */
public final class AdminUsersAccess {

    public static final String ADMIN_AREA_VIEW =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canViewArea(authentication, '" + Roles.ADMIN + "')";

    public static final String ADMIN_AREA_EDIT =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canEditArea(authentication, '" + Roles.ADMIN + "')";

    private AdminUsersAccess() {
    }
}
