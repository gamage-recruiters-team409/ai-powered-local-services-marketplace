package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

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

    @Test
    @DisplayName("Issue 6: Successfully create at least 3 admin accounts without phone (phone absent/null)")
    void shouldCreateMultipleAdminAccountsWithoutPhone() {
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
