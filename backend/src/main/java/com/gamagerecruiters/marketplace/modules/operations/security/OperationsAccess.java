package com.gamagerecruiters.marketplace.modules.operations.security;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

/**
 * {@code @PreAuthorize} expressions for the operations endpoints (WP09), using the approved admin area-access policy.
 *
 * <ul>
 *   <li><b>Reads</b>: the SUPPORT_ADMIN or ADMIN area (so SUPPORT_ADMIN and ADMIN can read their own area, and
 *       SUPER_ADMIN reads both read-only); every other role 403.</li>
 *   <li><b>Writes</b>: the SUPPORT_ADMIN or ADMIN area may edit; SUPER_ADMIN is read-only (403) and every other role
 *       403. The SUSPEND-vs-record branch (SUPPORT_ADMIN may not SUSPEND) is enforced in the service.</li>
 * </ul>
 */
public final class OperationsAccess {

    private static final String POLICY = "@" + AdminAccessPolicyService.BEAN_NAME;

    public static final String VIEW =
            POLICY + ".canViewArea(authentication, '" + Roles.SUPPORT_ADMIN + "') or "
            + POLICY + ".canViewArea(authentication, '" + Roles.ADMIN + "')";

    public static final String EDIT =
            POLICY + ".canEditArea(authentication, '" + Roles.SUPPORT_ADMIN + "') or "
            + POLICY + ".canEditArea(authentication, '" + Roles.ADMIN + "')";

    private OperationsAccess() {
    }
}
