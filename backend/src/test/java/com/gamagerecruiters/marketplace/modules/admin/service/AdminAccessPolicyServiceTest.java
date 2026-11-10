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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccessResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;

class AdminAccessPolicyServiceTest {

    private final AdminAccessPolicyService policy = new AdminAccessPolicyService();

    static Stream<Arguments> accessMatrix() {
        return Stream.of(
                Arguments.of(Role.SUPER_ADMIN, List.of(Role.SUPER_ADMIN),
                        List.of(Role.ADMIN, Role.SUPPORT_ADMIN, Role.MODERATOR, Role.FINANCE_ADMIN), true),
                Arguments.of(Role.ADMIN, List.of(Role.ADMIN),
                        List.of(Role.SUPPORT_ADMIN, Role.MODERATOR, Role.FINANCE_ADMIN), false),
                Arguments.of(Role.SUPPORT_ADMIN, List.of(Role.SUPPORT_ADMIN),
                        List.of(Role.ADMIN, Role.MODERATOR, Role.FINANCE_ADMIN), false),
                Arguments.of(Role.MODERATOR, List.of(Role.MODERATOR),
                        List.of(Role.ADMIN, Role.SUPPORT_ADMIN, Role.FINANCE_ADMIN), false),
                Arguments.of(Role.FINANCE_ADMIN, List.of(Role.FINANCE_ADMIN),
                        List.of(Role.ADMIN, Role.SUPPORT_ADMIN, Role.MODERATOR), false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accessMatrix")
    @DisplayName("getAccess: returns the expected edit/view matrix for each admin role")
    void getAccessReturnsMatrixForAdminRoles(Role role, List<Role> canEdit, List<Role> canView, boolean superAdmin) {
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
    @DisplayName("canEdit/canView: non-admin roles get no admin area")
    void nonAdminsCannotEditOrViewAnyArea(Role role) {
        for (Role area : Role.values()) {
            assertFalse(policy.canEdit(role, area), role + " must not edit " + area);
            assertFalse(policy.canView(role, area), role + " must not view " + area);
        }
        assertFalse(policy.canAccessSuperAdmin(role));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("Non-super admins can neither edit nor view the SUPER_ADMIN area")
    void nonSuperAdminsHaveNoSuperAdminAccess(Role role) {
        assertFalse(policy.canEdit(role, Role.SUPER_ADMIN));
        assertFalse(policy.canView(role, Role.SUPER_ADMIN));
        assertFalse(policy.canAccessSuperAdmin(role));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("canEdit and canView never overlap, and customer/provider areas are never exposed")
    void editAndViewDoNotOverlap(Role role) {
        AdminAccessResponse access = policy.getAccess(role);

        HashSet<Role> overlap = new HashSet<>(access.canEdit());
        overlap.retainAll(access.canView());
        assertTrue(overlap.isEmpty(), "Overlap for " + role + ": " + overlap);
        assertFalse(access.canView().contains(Role.CUSTOMER));
        assertFalse(access.canView().contains(Role.PROVIDER));
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

    private static UsernamePasswordAuthenticationToken authFor(Role role) {
        UserPrincipal principal = new UserPrincipal("user-1", "user@test.local", role);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
