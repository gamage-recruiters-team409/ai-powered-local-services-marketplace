package com.gamagerecruiters.marketplace.modules.dispute.security;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

/**
 * {@code @PreAuthorize} expressions for the dispute endpoints, built from the shared {@link Roles}
 * constants and the approved admin access policy.
 *
 * <p>The disputes admin area is the ADMIN area. Under the approved matrix, ADMIN can use it and
 * SUPER_ADMIN can view it read-only; every other role gets 403. Write (handling) endpoints in the next
 * PR must use {@code canEditArea} instead, so SUPER_ADMIN stays read-only.</p>
 */
public final class DisputeAccess {

    /** Booking parties: customers and providers. */
    public static final String PARTY =
            "hasAnyRole('" + Roles.CUSTOMER + "', '" + Roles.PROVIDER + "')";

    /** Read access to the disputes admin area (ADMIN; SUPER_ADMIN read-only). */
    public static final String ADMIN_AREA_VIEW =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canViewArea(authentication, '" + Roles.ADMIN + "')";

    /** A party, or an admin who can view the disputes area. Per-dispute party checks happen in the service. */
    public static final String PARTY_OR_ADMIN_AREA_VIEW = PARTY + " or " + ADMIN_AREA_VIEW;

    private DisputeAccess() {
    }
}
