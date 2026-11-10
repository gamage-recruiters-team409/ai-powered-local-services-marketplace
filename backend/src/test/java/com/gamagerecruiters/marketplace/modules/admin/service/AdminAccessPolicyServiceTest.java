package com.gamagerecruiters.marketplace.modules.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccessResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;

class AdminAccessPolicyServiceTest {

    private static final List<Role> NON_SUPER_AREAS =
            List.of(Role.ADMIN, Role.SUPPORT_ADMIN, Role.MODERATOR, Role.FINANCE_ADMIN);

    private final AdminAccessPolicyService policy = new AdminAccessPolicyService();

    /** The approved matrix: role, canEdit, canView, canAccessSuperAdmin. */
    static Stream<Arguments> approvedMatrix() {
        return Stream.of(
                Arguments.of(Role.SUPER_ADMIN, List.of(Role.SUPER_ADMIN), NON_SUPER_AREAS, true),
                Arguments.of(Role.ADMIN, List.of(Role.ADMIN), List.of(), false),
                Arguments.of(Role.SUPPORT_ADMIN, List.of(Role.SUPPORT_ADMIN), List.of(), false),
                Arguments.of(Role.MODERATOR, List.of(Role.MODERATOR), List.of(), false),
                Arguments.of(Role.FINANCE_ADMIN, List.of(Role.FINANCE_ADMIN), List.of(), false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("approvedMatrix")
    @DisplayName("getAccess: returns exactly the approved matrix for each admin role")
    void getAccessReturnsApprovedMatrix(Role role, List<Role> canEdit, List<Role> canView, boolean superAdmin) {
        AdminAccessResponse access = policy.getAccess(role);

        assertEquals(role, access.role());
        assertEquals(canEdit, access.canEdit());
        assertEquals(canView, access.canView());
        assertEquals(superAdmin, access.canAccessSuperAdmin());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @NullSource
    @DisplayName("getAccess: non-admin or missing role is denied")
    void getAccessDeniesNonAdmins(Role role) {
        assertFalse(policy.isAdmin(role));
        assertThrows(AccessDeniedException.class, () -> policy.getAccess(role));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @NullSource
    @DisplayName("canEdit/canView: non-admin or missing role gets no admin area")
    void nonAdminsCannotEditOrViewAnyArea(Role role) {
        for (Role area : Role.values()) {
            assertFalse(policy.canEdit(role, area), role + " must not edit " + area);
            assertFalse(policy.canView(role, area), role + " must not view " + area);
        }
        assertFalse(policy.canAccessSuperAdmin(role));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("Non-super admins edit only their own area and cannot view any other area")
    void nonSuperAdminsHaveOnlyTheirOwnArea(Role role) {
        for (Role area : Role.values()) {
            assertEquals(area == role, policy.canEdit(role, area), role + " edit " + area);
            assertFalse(policy.canView(role, area), role + " must not view " + area);
        }
        assertFalse(policy.canAccessSuperAdmin(role));
    }

    @Test
    @DisplayName("SUPER_ADMIN edits only its own area and views the four non-super areas read-only")
    void superAdminViewsOtherAreasReadOnly() {
        assertTrue(policy.canEdit(Role.SUPER_ADMIN, Role.SUPER_ADMIN));
        assertFalse(policy.canView(Role.SUPER_ADMIN, Role.SUPER_ADMIN));
        for (Role area : NON_SUPER_AREAS) {
            assertFalse(policy.canEdit(Role.SUPER_ADMIN, area), "SUPER_ADMIN must not edit " + area);
            assertTrue(policy.canView(Role.SUPER_ADMIN, area), "SUPER_ADMIN should view " + area);
        }
        assertFalse(policy.canView(Role.SUPER_ADMIN, Role.CUSTOMER));
        assertFalse(policy.canView(Role.SUPER_ADMIN, Role.PROVIDER));
        assertTrue(policy.canAccessSuperAdmin(Role.SUPER_ADMIN));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("canEdit and canView never overlap")
    void editAndViewDoNotOverlap(Role role) {
        AdminAccessResponse access = policy.getAccess(role);

        HashSet<Role> overlap = new HashSet<>(access.canEdit());
        overlap.retainAll(access.canView());
        assertTrue(overlap.isEmpty(), "Overlap for " + role + ": " + overlap);
    }

    @Test
    @DisplayName("Admin areas match the shared Roles.ADMIN_ROLES definition")
    void adminAreasMatchSharedAdminRoles() {
        assertEquals(Roles.ADMIN_ROLES, new HashSet<>(AdminAccessPolicyService.ADMIN_AREAS));
    }

    @Test
    @DisplayName("isAdmin(Authentication): true only for an authenticated admin principal")
    void isAdminFromAuthentication() {
        assertTrue(policy.isAdmin(authFor(Role.MODERATOR)));
        assertFalse(policy.isAdmin(authFor(Role.CUSTOMER)));
        assertFalse(policy.isAdmin((Authentication) null));
        assertFalse(policy.isAdmin(new UsernamePasswordAuthenticationToken("not-a-principal", null)));
    }

    @Test
    @DisplayName("canEditArea/canViewArea: follow the approved matrix for the authenticated principal")
    void areaChecksFromAuthentication() {
        assertTrue(policy.canEditArea(authFor(Role.MODERATOR), Role.MODERATOR));
        assertFalse(policy.canEditArea(authFor(Role.MODERATOR), Role.ADMIN));
        assertFalse(policy.canViewArea(authFor(Role.MODERATOR), Role.ADMIN));
        assertTrue(policy.canViewArea(authFor(Role.MODERATOR), Role.MODERATOR));

        assertTrue(policy.canViewArea(authFor(Role.SUPER_ADMIN), Role.FINANCE_ADMIN));
        assertFalse(policy.canEditArea(authFor(Role.SUPER_ADMIN), Role.FINANCE_ADMIN));

        assertFalse(policy.canViewArea(authFor(Role.CUSTOMER), Role.ADMIN));
        assertFalse(policy.canEditArea(null, Role.ADMIN));
        assertFalse(policy.canViewArea(null, Role.ADMIN));
    }

    @Test
    @DisplayName("SpEL: area names given as strings convert to Role, as in the documented @PreAuthorize usage")
    void spelConvertsAreaNamesToRole() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("policy", policy);
        context.setVariable("auth", authFor(Role.MODERATOR));
        SpelExpressionParser parser = new SpelExpressionParser();

        assertEquals(Boolean.TRUE,
                parser.parseExpression("#policy.canEditArea(#auth, 'MODERATOR')").getValue(context, Boolean.class));
        assertEquals(Boolean.FALSE,
                parser.parseExpression("#policy.canViewArea(#auth, 'ADMIN')").getValue(context, Boolean.class));
    }

    private static UsernamePasswordAuthenticationToken authFor(Role role) {
        UserPrincipal principal = new UserPrincipal("user-1", "user@test.local", role);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
