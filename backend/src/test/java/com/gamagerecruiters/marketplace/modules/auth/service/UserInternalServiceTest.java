package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;

@ExtendWith(MockitoExtension.class)
class UserInternalServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthTokenRepository authTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserInternalServiceImpl userInternalService;

    @BeforeEach
    void setUp() {
        userInternalService = new UserInternalServiceImpl(userRepository, authTokenRepository, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId, String email, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, email, role);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private User createUser(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@marketplace.local");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    @Test
    @DisplayName("Issue A: ADMIN -> SUPER_ADMIN status change is denied (403 ROLE_HIERARCHY_VIOLATION)")
    void adminCannotChangeSuperAdminStatus() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-1")).thenReturn(Optional.of(targetSuper));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changeStatus("super-1", UserStatus.SUSPENDED)
        );
        assertEquals("ROLE_HIERARCHY_VIOLATION", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("not permitted"));
        verify(userRepository, never()).save(targetSuper);
    }

    @Test
    @DisplayName("Issue A: ADMIN -> ADMIN status change is denied (403 ROLE_HIERARCHY_VIOLATION)")
    void adminCannotChangeAnotherAdminStatus() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetAdmin = createUser("admin-2", Role.ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-2")).thenReturn(Optional.of(targetAdmin));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changeStatus("admin-2", UserStatus.SUSPENDED)
        );
        assertEquals("ROLE_HIERARCHY_VIOLATION", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("not permitted"));
        verify(userRepository, never()).save(targetAdmin);
    }

    @Test
    @DisplayName("Issue A: ADMIN -> CUSTOMER status change is allowed")
    void adminCanChangeCustomerStatus() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetCust = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(targetCust));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userInternalService.changeStatus("cust-1", UserStatus.SUSPENDED);
        assertEquals(UserStatus.SUSPENDED, updated.getStatus());
        verify(userRepository).save(targetCust);
    }

    @Test
    @DisplayName("Issue A: SUPER_ADMIN -> ADMIN status change is allowed")
    void superAdminCanChangeAdminStatus() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetAdmin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(targetAdmin));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userInternalService.changeStatus("admin-1", UserStatus.SUSPENDED);
        assertEquals(UserStatus.SUSPENDED, updated.getStatus());
        verify(userRepository).save(targetAdmin);
    }

    @Test
    @DisplayName("Issue A: SUPER_ADMIN modifying own status is denied (403 CANNOT_MODIFY_SELF)")
    void superAdminCannotModifyOwnStatus() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changeStatus("super-1", UserStatus.SUSPENDED)
        );
        assertEquals("CANNOT_MODIFY_SELF", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("Self status modification is not allowed", ex.getMessage());
        verify(userRepository, never()).save(actor);
    }

    @Test
    @DisplayName("Issue A: Suspending last remaining ACTIVE SUPER_ADMIN is denied (409 LAST_ACTIVE_SUPER_ADMIN)")
    void cannotSuspendLastRemainingActiveSuperAdmin() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(targetSuper));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changeStatus("super-2", UserStatus.SUSPENDED)
        );
        assertEquals("LAST_ACTIVE_SUPER_ADMIN", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("last remaining ACTIVE SUPER_ADMIN"));
        verify(userRepository, never()).save(targetSuper);
    }

    @Test
    @DisplayName("Issue A: createAdminAccount with SUPER_ADMIN role must be rejected")
    void shouldRejectCreatingSuperAdminAccount() {
        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.createAdminAccount("Super Two", "super2@marketplace.local", "Pass123", Role.SUPER_ADMIN)
        );
        assertEquals("INVALID_ROLE", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("startup seed only"));
    }

    @Test
    @DisplayName("Issue A: createAdminAccount with non-SUPER_ADMIN actor is denied")
    void shouldDenyCreateAdminAccountByNonSuperAdmin() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> userInternalService.createAdminAccount("New Admin", "newadmin@marketplace.local", "Pass123", Role.ADMIN)
        );
        assertTrue(ex.getMessage().contains("Only an active SUPER_ADMIN"));
    }

    @Test
    @DisplayName("Issue 6: Successfully create at least 3 admin accounts without phone (phone absent/null)")
    void shouldCreateMultipleAdminAccountsWithoutPhone() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User superActor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("super-1")).thenReturn(Optional.of(superActor));

        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pwd");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("id-" + u.getEmail());
            return u;
        });

        // Admin 1
        User admin1 = userInternalService.createAdminAccount("Admin One", "admin1@marketplace.local", "AdminPass1", Role.ADMIN);
        assertNotNull(admin1);
        assertNull(admin1.getPhone(), "Phone must be absent/null for admin accounts");
        assertEquals(Role.ADMIN, admin1.getRole());
        assertEquals(UserStatus.ACTIVE, admin1.getStatus());
        assertTrue(admin1.isMustChangePassword(), "Admin created via createAdminAccount must have mustChangePassword=true");

        // Admin 2: Support Admin
        User admin2 = userInternalService.createAdminAccount("Admin Two", "admin2@marketplace.local", "AdminPass2", Role.SUPPORT_ADMIN);
        assertNotNull(admin2);
        assertNull(admin2.getPhone(), "Phone must be absent/null for admin accounts");
        assertEquals(Role.SUPPORT_ADMIN, admin2.getRole());

        // Admin 3: Finance Admin
        User admin3 = userInternalService.createAdminAccount("Admin Three", "admin3@marketplace.local", "AdminPass3", Role.FINANCE_ADMIN);
        assertNotNull(admin3);
        assertNull(admin3.getPhone(), "Phone must be absent/null for admin accounts");
        assertEquals(Role.FINANCE_ADMIN, admin3.getRole());
    }

    @Test
    @DisplayName("Issue 6: Duplicate email in createAdminAccount should throw ResourceConflictException")
    void shouldRejectDuplicateEmailOnAdminCreation() {
        when(userRepository.existsByEmail("duplicate@marketplace.local")).thenReturn(true);

        assertThrows(
                ResourceConflictException.class,
                () -> userInternalService.createAdminAccount("Admin Dupe", "duplicate@marketplace.local", "Pass123", Role.ADMIN)
        );
    }

    @Test
    @DisplayName("Issue 6: Non-administrative role should be rejected on createAdminAccount")
    void shouldRejectNonAdminRole() {
        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.createAdminAccount("Fake Admin", "fake@marketplace.local", "Pass123", Role.CUSTOMER)
        );
        assertEquals("INVALID_ROLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("updateContactDetails: Should update name and normalize Sri Lankan phone successfully")
    void shouldUpdateContactDetailsWithNormalizedPhone() {
        User user = new User();
        user.setId("u-1");
        user.setName("Old Name");
        user.setPhone("+94711111111");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(userRepository.findByPhone("+94771234567")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userInternalService.updateContactDetails("u-1", "New Name", "0771234567");

        assertEquals("New Name", updated.getName());
        assertEquals("+94771234567", updated.getPhone());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("updateContactDetails: Should reject phone already used by another user")
    void shouldRejectDuplicatePhoneOnContactUpdate() {
        User user = new User();
        user.setId("u-1");

        User other = new User();
        other.setId("u-2");
        other.setPhone("+94771234567");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(userRepository.findByPhone("+94771234567")).thenReturn(Optional.of(other));

        ResourceConflictException ex = assertThrows(
                ResourceConflictException.class,
                () -> userInternalService.updateContactDetails("u-1", "New Name", "0771234567")
        );
        assertEquals("PHONE_ALREADY_EXISTS", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateContactDetails: Should allow keeping own phone number")
    void shouldAllowKeepingOwnPhoneOnContactUpdate() {
        User user = new User();
        user.setId("u-1");
        user.setPhone("+94771234567");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(userRepository.findByPhone("+94771234567")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userInternalService.updateContactDetails("u-1", "Updated Name", "+94771234567");
        assertEquals("Updated Name", updated.getName());
        assertEquals("+94771234567", updated.getPhone());
    }

    @Test
    @DisplayName("updateContactDetails: Should reject invalid Sri Lankan phone format")
    void shouldRejectInvalidPhoneOnContactUpdate() {
        User user = new User();
        user.setId("u-1");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.updateContactDetails("u-1", "Name", "12345")
        );
        assertEquals("INVALID_PHONE_FORMAT", ex.getErrorCode());
    }

    @Test
    @DisplayName("changePassword: Valid current and new password should update hash, set mustChangePassword=false and revoke tokens")
    void shouldChangePasswordAndRevokeTokens() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-old-pass");
        user.setMustChangePassword(true);

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "hashed-old-pass")).thenReturn(true);
        when(passwordEncoder.matches("NewPass123!", "hashed-old-pass")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123!")).thenReturn("hashed-new-pass");

        userInternalService.changePassword("u-1", "OldPass123!", "NewPass123!");

        assertEquals("hashed-new-pass", user.getPasswordHash());
        assertFalse(user.isMustChangePassword(), "mustChangePassword must be reset to false after successful change");
        verify(userRepository).save(user);
        verify(authTokenRepository).revokeAllActiveTokensByUserId("u-1");
    }

    @Test
    @DisplayName("Legacy user document without mustChangePassword field defaults safely to false")
    void shouldDefaultMustChangePasswordToFalseForLegacyUser() {
        User legacyUser = new User();
        assertFalse(legacyUser.isMustChangePassword(), "Default mustChangePassword for legacy user must be false");
    }

    @Test
    @DisplayName("changePassword: Wrong current password should be rejected with 400")
    void shouldRejectWrongCurrentPassword() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-old-pass");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass123!", "hashed-old-pass")).thenReturn(false);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changePassword("u-1", "WrongPass123!", "NewPass123!")
        );
        assertEquals("INVALID_CURRENT_PASSWORD", ex.getErrorCode());
        assertEquals("Current password is incorrect", ex.getMessage());
        verify(userRepository, never()).save(any());
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserId(any());
    }

    @Test
    @DisplayName("changePassword: Same new password as current should be rejected with 400")
    void shouldRejectSamePassword() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-old-pass");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "hashed-old-pass")).thenReturn(true);
        when(passwordEncoder.matches("OldPass123!", "hashed-old-pass")).thenReturn(true);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changePassword("u-1", "OldPass123!", "OldPass123!")
        );
        assertEquals("SAME_PASSWORD", ex.getErrorCode());
        assertEquals("New password cannot be the same as the current password", ex.getMessage());
    }

    @Test
    @DisplayName("changePassword: Weak new password should be rejected with 400")
    void shouldRejectWeakNewPassword() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-old-pass");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "hashed-old-pass")).thenReturn(true);
        when(passwordEncoder.matches("weak", "hashed-old-pass")).thenReturn(false);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changePassword("u-1", "OldPass123!", "weak")
        );
        assertEquals("WEAK_PASSWORD", ex.getErrorCode());
    }

    @Test
    @DisplayName("verifyPassword: Valid password passes without exception")
    void shouldVerifyPasswordSuccessfully() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-pass");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass123!", "hashed-pass")).thenReturn(true);

        assertDoesNotThrow(() -> userInternalService.verifyPassword("u-1", "Pass123!"));
    }

    @Test
    @DisplayName("verifyPassword: Wrong password throws 400 Bad Request")
    void shouldRejectInvalidPasswordOnVerification() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-pass");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "hashed-pass")).thenReturn(false);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.verifyPassword("u-1", "WrongPass")
        );
        assertEquals("INVALID_PASSWORD", ex.getErrorCode());
        assertEquals("Password confirmation is incorrect", ex.getMessage());
    }

    @Test
    @DisplayName("deactivateOwnAccount: Active user transitions to DEACTIVATED and revokes all tokens")
    void shouldDeactivateActiveAccountAndRevokeTokens() {
        User user = new User();
        user.setId("u-1");
        user.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

        userInternalService.deactivateOwnAccount("u-1");

        assertEquals(UserStatus.DEACTIVATED, user.getStatus());
        verify(userRepository).save(user);
        verify(authTokenRepository).revokeAllActiveTokensByUserId("u-1");
    }

    @Test
    @DisplayName("deactivateOwnAccount: Non-active user throws 400 Bad Request")
    void shouldRejectDeactivatingNonActiveAccount() {
        User user = new User();
        user.setId("u-1");
        user.setStatus(UserStatus.SUSPENDED);

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.deactivateOwnAccount("u-1")
        );
        assertEquals("ACCOUNT_NOT_ACTIVE", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserId(any());
    }

    @Test
    @DisplayName("changeStatus: Admin cannot modify status of a DEACTIVATED account (403 ACCOUNT_DEACTIVATED)")
    void shouldRejectAdminModifyingDeactivatedAccount() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);

        User actor = new User();
        actor.setId("admin-1");
        actor.setRole(Role.ADMIN);
        actor.setStatus(UserStatus.ACTIVE);

        User target = new User();
        target.setId("cust-1");
        target.setRole(Role.CUSTOMER);
        target.setStatus(UserStatus.DEACTIVATED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(target));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changeStatus("cust-1", UserStatus.ACTIVE)
        );
        assertEquals("ACCOUNT_DEACTIVATED", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("Cannot modify status of a DEACTIVATED account"));
        verify(userRepository, never()).save(target);
    }
}
