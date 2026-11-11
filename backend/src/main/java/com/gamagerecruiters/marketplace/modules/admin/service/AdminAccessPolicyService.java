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
 * Single source of truth for the approved, least-privilege admin area access matrix.
 *
 * <h2>Approved matrix</h2>
 * <table>
 *   <caption>Admin area access per role</caption>
 *   <tr><th>Role</th><th>canEdit</th><th>canView (read-only)</th><th>canAccessSuperAdmin</th></tr>
 *   <tr><td>SUPER_ADMIN</td><td>SUPER_ADMIN</td><td>ADMIN, SUPPORT_ADMIN, MODERATOR, FINANCE_ADMIN</td><td>true</td></tr>
 *   <tr><td>ADMIN</td><td>ADMIN</td><td>none</td><td>false</td></tr>
 *   <tr><td>SUPPORT_ADMIN</td><td>SUPPORT_ADMIN</td><td>none</td><td>false</td></tr>
 *   <tr><td>MODERATOR</td><td>MODERATOR</td><td>none</td><td>false</td></tr>
 *   <tr><td>FINANCE_ADMIN</td><td>FINANCE_ADMIN</td><td>none</td><td>false</td></tr>
 *   <tr><td>CUSTOMER, PROVIDER, any other or null</td><td colspan="3">no admin access (403)</td></tr>
 * </table>
 * <p>{@code canView} never overlaps {@code canEdit}. Only SUPER_ADMIN has read-only access to other areas;
 * the other admin roles have no general view access to any area except their own.</p>
 *
 * <h2>Backend enforcement is mandatory</h2>
 * <p>The frontend uses {@code GET /api/v1/admin/access} only to build navigation and to show read-only
 * states. That is not a security control. Every admin read or write endpoint added in the future must
 * enforce this policy on the backend itself, independently of what the frontend shows, for example
 * {@code @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'MODERATOR')")} for a
 * write, or {@code canViewArea} for a read, plus any per-resource checks the operation needs.</p>
 *
 * <h2>Cross-module access: targeted operations only</h2>
 * <p>An admin role never gains access to another admin area or module as a whole. When a business
 * operation genuinely needs another module's data, that access is granted for that specific,
 * authorized operation only. Example (future work, not implemented here): when an ADMIN handles a
 * dispute, the dispute module may fetch only the payment details related to that dispute, through the
 * Payment module's service (never its repository). That integration must:</p>
 * <ul>
 *   <li>enforce backend authorization for the operation (the caller may handle this dispute);</li>
 *   <li>verify the relationship between the dispute and the payment before returning anything;</li>
 *   <li>expose only the fields the operation needs, through a dedicated DTO.</li>
 * </ul>
 *
 * <p>Also used from {@code @PreAuthorize} expressions as {@code @adminAccessPolicyService}.</p>
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
        return isAdmin(roleOf(authentication));
    }

    /** Every admin role may edit its own area, and only its own area. */
    public boolean canEdit(Role actor, Role area) {
        return isAdmin(actor) && actor == area;
    }

    /**
     * Read-only access to another admin area. Only SUPER_ADMIN has it, for the four non-super areas.
     * An area the actor can edit is never reported as viewable.
     */
    public boolean canView(Role actor, Role area) {
        return actor == Role.SUPER_ADMIN && isAdmin(area) && area != Role.SUPER_ADMIN;
    }

    public boolean canAccessSuperAdmin(Role actor) {
        return actor == Role.SUPER_ADMIN;
    }

    /** For future write endpoints: {@code @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'ADMIN')")}. */
    public boolean canEditArea(Authentication authentication, Role area) {
        return canEdit(roleOf(authentication), area);
    }

    /** For future read endpoints: true if the caller can edit the area or view it read-only. */
    public boolean canViewArea(Authentication authentication, Role area) {
        Role actor = roleOf(authentication);
        return canEdit(actor, area) || canView(actor, area);
    }

    public AdminAccessResponse getAccess(Role actor) {
        if (!isAdmin(actor)) {
            throw new AccessDeniedException("Admin access is restricted to admin roles");
        }
        List<Role> editable = ADMIN_AREAS.stream().filter(area -> canEdit(actor, area)).toList();
        List<Role> viewable = ADMIN_AREAS.stream().filter(area -> canView(actor, area)).toList();
        return new AdminAccessResponse(actor, editable, viewable, canAccessSuperAdmin(actor));
    }

    private static Role roleOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getRole();
        }
        return null;
    }
}
