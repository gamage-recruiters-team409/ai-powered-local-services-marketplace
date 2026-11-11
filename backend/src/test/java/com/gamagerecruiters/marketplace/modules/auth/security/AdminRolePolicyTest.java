package com.gamagerecruiters.marketplace.modules.auth.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminRolePolicyTest {

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("canManage: SUPER_ADMIN can manage all target roles")
    void superAdminCanManageAllRoles() {
        for (Role role : Role.values()) {
            assertTrue(AdminRolePolicy.canManage(Role.SUPER_ADMIN, role), "SUPER_ADMIN should be able to manage " + role);
        }
    }

    @Test
    @DisplayName("canManage: ADMIN can manage only CUSTOMER and PROVIDER")
    void adminCanManageOnlyCustomerAndProvider() {
        assertTrue(AdminRolePolicy.canManage(Role.ADMIN, Role.CUSTOMER));
        assertTrue(AdminRolePolicy.canManage(Role.ADMIN, Role.PROVIDER));

        assertFalse(AdminRolePolicy.canManage(Role.ADMIN, Role.ADMIN));
        assertFalse(AdminRolePolicy.canManage(Role.ADMIN, Role.SUPER_ADMIN));
        assertFalse(AdminRolePolicy.canManage(Role.ADMIN, Role.SUPPORT_ADMIN));
        assertFalse(AdminRolePolicy.canManage(Role.ADMIN, Role.FINANCE_ADMIN));
        assertFalse(AdminRolePolicy.canManage(Role.ADMIN, Role.MODERATOR));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "FINANCE_ADMIN", "MODERATOR", "CUSTOMER", "PROVIDER"})
    @DisplayName("canManage: Other roles cannot manage any target role")
    void otherRolesCannotManageAnyRole(Role role) {
        for (Role targetRole : Role.values()) {
            assertFalse(AdminRolePolicy.canManage(role, targetRole), role + " should not manage " + targetRole);
        }
    }

    @Test
    @DisplayName("validateStatusChange: ADMIN -> CUSTOMER is allowed")
    void adminCanChangeCustomerStatus() {
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User customer = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);

        assertDoesNotThrow(() -> AdminRolePolicy.validateStatusChange(admin, customer, UserStatus.SUSPENDED, userRepository));
    }

    @Test
    @DisplayName("validateStatusChange: ADMIN -> PROVIDER is allowed")
    void adminCanChangeProviderStatus() {
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User provider = createUser("prov-1", Role.PROVIDER, UserStatus.ACTIVE);

        assertDoesNotThrow(() -> AdminRolePolicy.validateStatusChange(admin, provider, UserStatus.SUSPENDED, userRepository));
    }

    @Test
    @DisplayName("validateStatusChange: ADMIN -> SUPER_ADMIN is denied with 403 AccessDeniedException")
    void adminCannotChangeSuperAdminStatus() {
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User superAdmin = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(admin, superAdmin, UserStatus.SUSPENDED, userRepository)
        );
        assertTrue(ex.getMessage().contains("not permitted"));
    }

    @Test
    @DisplayName("validateStatusChange: ADMIN -> ADMIN is denied with 403 AccessDeniedException")
    void adminCannotChangeAnotherAdminStatus() {
        User admin1 = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User admin2 = createUser("admin-2", Role.ADMIN, UserStatus.ACTIVE);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(admin1, admin2, UserStatus.SUSPENDED, userRepository)
        );
        assertTrue(ex.getMessage().contains("not permitted"));
    }

    @Test
    @DisplayName("validateStatusChange: Self status modification is strictly denied")
    void selfStatusModificationDenied() {
        User superAdmin = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(superAdmin, superAdmin, UserStatus.SUSPENDED, userRepository)
        );
        assertEquals("Self status modification is not allowed", ex.getMessage());
    }

    @Test
    @DisplayName("validateStatusChange: Last remaining ACTIVE SUPER_ADMIN cannot be suspended/deactivated/locked")
    void lastRemainingActiveSuperAdminCannotBeSuspended() {
        User actorSuperAdmin = createUser("super-actor", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetSuperAdmin = createUser("super-target", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(actorSuperAdmin, targetSuperAdmin, UserStatus.SUSPENDED, userRepository)
        );
        assertTrue(ex.getMessage().contains("last remaining ACTIVE SUPER_ADMIN"));
    }

    @Test
    @DisplayName("validateStatusChange: SUPER_ADMIN can suspend another SUPER_ADMIN if more than one active exists")
    void superAdminCanSuspendAnotherSuperAdminIfMultipleActiveExist() {
        User actorSuperAdmin = createUser("super-actor", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetSuperAdmin = createUser("super-target", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);

        assertDoesNotThrow(() -> AdminRolePolicy.validateStatusChange(actorSuperAdmin, targetSuperAdmin, UserStatus.SUSPENDED, userRepository));
    }

    @Test
    @DisplayName("validateStatusChange: Non-admin role cannot change status")
    void nonAdminCannotChangeStatus() {
        User customer = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);
        User target = createUser("target-1", Role.PROVIDER, UserStatus.ACTIVE);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(customer, target, UserStatus.SUSPENDED, userRepository)
        );
        assertTrue(ex.getMessage().contains("not permitted"));
    }

    @Test
    @DisplayName("validateStatusChange: DEACTIVATED target account is terminal and cannot be modified by ADMIN")
    void adminCannotModifyDeactivatedAccount() {
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User deactivatedCustomer = createUser("cust-1", Role.CUSTOMER, UserStatus.DEACTIVATED);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(admin, deactivatedCustomer, UserStatus.ACTIVE, userRepository)
        );
        assertTrue(ex.getMessage().contains("Cannot modify status of a DEACTIVATED account"));
    }

    @Test
    @DisplayName("validateStatusChange: DEACTIVATED target account is terminal and cannot be modified by SUPER_ADMIN")
    void superAdminCannotModifyDeactivatedAccount() {
        User superAdmin = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User deactivatedCustomer = createUser("cust-1", Role.CUSTOMER, UserStatus.DEACTIVATED);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> AdminRolePolicy.validateStatusChange(superAdmin, deactivatedCustomer, UserStatus.ACTIVE, userRepository)
        );
        assertTrue(ex.getMessage().contains("Cannot modify status of a DEACTIVATED account"));
    }

    private User createUser(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@marketplace.local");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
