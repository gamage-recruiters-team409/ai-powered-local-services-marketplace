package com.gamagerecruiters.marketplace.modules.announcement.security;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

/**
 * {@code @PreAuthorize} expressions for the admin announcement endpoints: the same approved admin access policy as
 * the dispute admin endpoints (the ADMIN area). ADMIN can read and write; SUPER_ADMIN can read only (writes get 403);
 * every other role gets 403.
 */
public final class AnnouncementAccess {

    /** Read access (ADMIN; SUPER_ADMIN read-only). */
    public static final String ADMIN_AREA_VIEW =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canViewArea(authentication, '" + Roles.ADMIN + "')";

    /** Write access (ADMIN only). */
    public static final String ADMIN_AREA_EDIT =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canEditArea(authentication, '" + Roles.ADMIN + "')";

    private AnnouncementAccess() {
    }
}
