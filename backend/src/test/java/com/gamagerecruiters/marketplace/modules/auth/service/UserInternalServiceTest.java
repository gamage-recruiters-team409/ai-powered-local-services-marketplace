package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;

@ExtendWith(MockitoExtension.class)
class UserInternalServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserInternalServiceImpl userInternalService;

    @BeforeEach
    void setUp() {
        userInternalService = new UserInternalServiceImpl(userRepository, passwordEncoder);
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
    @DisplayName("Issue A: ADMIN -> SUPER_ADMIN status change is denied (403 AccessDeniedException)")
    void adminCannotChangeSuperAdminStatus() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-1")).thenReturn(Optional.of(targetSuper));

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> userInternalService.changeStatus("super-1", UserStatus.SUSPENDED)
        );
        assertTrue(ex.getMessage().contains("not permitted"));
        verify(userRepository, never()).save(targetSuper);
    }

    @Test
    @DisplayName("Issue A: ADMIN -> ADMIN status change is denied (403 AccessDeniedException)")
    void adminCannotChangeAnotherAdminStatus() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetAdmin = createUser("admin-2", Role.ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-2")).thenReturn(Optional.of(targetAdmin));

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> userInternalService.changeStatus("admin-2", UserStatus.SUSPENDED)
        );
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
    @DisplayName("Issue A: SUPER_ADMIN modifying own status is denied")
    void superAdminCannotModifyOwnStatus() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> userInternalService.changeStatus("super-1", UserStatus.SUSPENDED)
        );
        assertEquals("Self status modification is not allowed", ex.getMessage());
        verify(userRepository, never()).save(actor);
    }

    @Test
    @DisplayName("Issue A: Suspending last remaining ACTIVE SUPER_ADMIN is denied")
    void cannotSuspendLastRemainingActiveSuperAdmin() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(targetSuper));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> userInternalService.changeStatus("super-2", UserStatus.SUSPENDED)
        );
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
}
