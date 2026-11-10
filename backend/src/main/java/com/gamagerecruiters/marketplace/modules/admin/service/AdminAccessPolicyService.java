package com.gamagerecruiters.marketplace.modules.admin.service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccessResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;

/**
 * Single source of truth for which admin areas each admin role may edit or view.
 *
 * <ul>
 *   <li>SUPER_ADMIN edits the SUPER_ADMIN area and views the four other admin areas read-only.</li>
 *   <li>ADMIN, SUPPORT_ADMIN, MODERATOR and FINANCE_ADMIN edit only their own area, view the other
 *       three of these areas read-only, and cannot access the SUPER_ADMIN area.</li>
 *   <li>Any non-admin role has no admin access.</li>
 * </ul>
 *
 * Also used from {@code @PreAuthorize} expressions as {@code @adminAccessPolicyService}.
 */
@Service(AdminAccessPolicyService.BEAN_NAME)
public class AdminAccessPolicyService {

    /** Explicit bean name so {@code @PreAuthorize} SpEL references resolve however the bean is registered. */
    public static final String BEAN_NAME = "adminAccessPolicyService";

    /** Admin areas in a fixed display order, so responses are deterministic. */
    static final List<Role> ADMIN_AREAS = List.of(
            Role.SUPER_ADMIN,
            Role.ADMIN,
            Role.SUPPORT_ADMIN,
            Role.MODERATOR,
            Role.FINANCE_ADMIN
    );

    public boolean isAdmin(Role role) {
        return role != null && Roles.ADMIN_ROLES.contains(role);
    }

    /** For {@code @PreAuthorize("@adminAccessPolicyService.isAdmin(authentication)")}. */
    public boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && isAdmin(principal.getRole());
    }

    public boolean canEdit(Role actor, Role area) {
        return isAdmin(actor) && actor == area;
    }

    /** Read-only access only: an area the actor can edit is not reported as viewable. */
    public boolean canView(Role actor, Role area) {
        if (!isAdmin(actor) || !isAdmin(area) || actor == area) {
            return false;
        }
        return area != Role.SUPER_ADMIN;
    }

    public boolean canAccessSuperAdmin(Role actor) {
        return actor == Role.SUPER_ADMIN;
    }

    public AdminAccessResponse getAccess(Role actor) {
        if (!isAdmin(actor)) {
            throw new AccessDeniedException("Admin access is restricted to admin roles");
        }
        List<Role> editable = ADMIN_AREAS.stream().filter(area -> canEdit(actor, area)).toList();
        List<Role> viewable = ADMIN_AREAS.stream().filter(area -> canView(actor, area)).toList();
        return new AdminAccessResponse(actor, editable, viewable, canAccessSuperAdmin(actor));
    }
}
