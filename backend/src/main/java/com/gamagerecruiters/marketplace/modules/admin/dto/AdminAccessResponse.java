package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * Admin area access for the authenticated user.
 *
 * @param role                the caller's role, taken from the authenticated principal
 * @param canEdit             admin areas the caller may edit (always only their own area)
 * @param canView             admin areas the caller may view read-only (never overlaps canEdit)
 * @param canAccessSuperAdmin whether the caller may open the Super Admin area
 */
public record AdminAccessResponse(
        Role role,
        List<Role> canEdit,
        List<Role> canView,
        boolean canAccessSuperAdmin
) {
}
