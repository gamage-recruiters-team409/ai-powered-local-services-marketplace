package com.gamagerecruiters.marketplace.modules.admin.security;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

/**
 * {@code @PreAuthorize} expression for the Super Admin area, built from the shared {@link Roles} constants
 * and the approved access policy. Only SUPER_ADMIN can edit its own area; every other role gets 403.
 */
public final class SuperAdminAccess {

    /** Edit access to the Super Admin area: {@code AdminAccessPolicyService.canEditArea(authentication, SUPER_ADMIN)}. */
    public static final String SUPER_ADMIN_AREA_EDIT =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canEditArea(authentication, '" + Roles.SUPER_ADMIN + "')";

    private SuperAdminAccess() {
    }
}
