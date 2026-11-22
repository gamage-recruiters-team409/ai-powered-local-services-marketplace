package com.gamagerecruiters.marketplace.modules.review.security;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

/**
 * {@code @PreAuthorize} expressions for review moderation. Review moderation is part of the MODERATOR admin
 * area ("Reported content and review moderation"); who can view or act there is decided by the shared
 * admin access policy (the same rules the admin pages use).
 */
public final class ReviewModerationAccess {

    /** Read the moderation queue and review details. */
    public static final String AREA_VIEW =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canViewArea(authentication, '" + Roles.MODERATOR + "')";

    /** Publish or remove a review. */
    public static final String AREA_EDIT =
            "@" + AdminAccessPolicyService.BEAN_NAME + ".canEditArea(authentication, '" + Roles.MODERATOR + "')";

    /** Report a review: signed-in customers and providers. */
    public static final String REPORTER =
            "hasAnyRole('" + Roles.CUSTOMER + "', '" + Roles.PROVIDER + "')";

    private ReviewModerationAccess() {
    }
}
