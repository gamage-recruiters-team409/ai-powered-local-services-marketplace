package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.mongodb.MongoException;
import com.mongodb.client.result.UpdateResult;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;
import com.gamagerecruiters.marketplace.modules.auth.dto.PeriodCount;
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

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    private UserInternalServiceImpl userInternalService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        userInternalService = new UserInternalServiceImpl(userRepository, authTokenRepository, passwordEncoder, mongoTemplate, transactionManager);
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
        user.setName("User " + id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    @Test
    @DisplayName("getContactById returns correct UserContact record")
    void getContactById_returnsUserContact() {
        User user = createUser("user-1", Role.CUSTOMER, UserStatus.ACTIVE);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        UserInternalService.UserContact contact = userInternalService.getContactById("user-1");

        assertNotNull(contact);
        assertEquals("user-1", contact.id());
        assertEquals("User user-1", contact.fullName());
        assertEquals("user-1@marketplace.local", contact.email());
        assertEquals(UserStatus.ACTIVE, contact.status());
    }

    @Test
    @DisplayName("Issue A: ADMIN -> SUPER_ADMIN status change is denied (403 ROLE_HIERARCHY_VIOLATION)")
    void adminCannotChangeSuperAdminStatus() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-1")).thenReturn(Optional.of(targetSuper));
        // Guard is acquired before policy check since target is SUPER_ADMIN and newStatus != ACTIVE
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document());

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
        User updatedCust = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1"))
                .thenReturn(Optional.of(targetCust))
                .thenReturn(Optional.of(targetCust))
                .thenReturn(Optional.of(updatedCust));
        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User updated = userInternalService.changeStatus("cust-1", UserStatus.SUSPENDED);
        assertEquals(UserStatus.SUSPENDED, updated.getStatus());
        // Successful status change to SUSPENDED revokes tokens twice (Step 1 pre-update and Step 3 post-update best-effort)
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Issue A: SUPER_ADMIN -> ADMIN status change is allowed")
    void superAdminCanChangeAdminStatus() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetAdmin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User updatedAdmin = createUser("admin-1", Role.ADMIN, UserStatus.SUSPENDED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-1"))
                .thenReturn(Optional.of(targetAdmin))
                .thenReturn(Optional.of(targetAdmin))
                .thenReturn(Optional.of(updatedAdmin));
        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User updated = userInternalService.changeStatus("admin-1", UserStatus.SUSPENDED);
        assertEquals(UserStatus.SUSPENDED, updated.getStatus());
        // Successful status change to SUSPENDED revokes tokens twice (Step 1 pre-update and Step 3 post-update best-effort)
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("admin-1", TokenType.REFRESH);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Issue A: SUPER_ADMIN modifying own status is denied (403 CANNOT_MODIFY_SELF)")
    void superAdminCannotModifyOwnStatus() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        // Guard is acquired before policy check since target is SUPER_ADMIN and newStatus != ACTIVE
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document());

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
        // Guard is acquired before policy check since target is SUPER_ADMIN and newStatus != ACTIVE
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document());

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
        when(userRepository.findByPhoneIn(any())).thenReturn(List.of());
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
        when(userRepository.findByPhoneIn(any())).thenReturn(List.of(other));

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
        when(userRepository.findByPhoneIn(any())).thenReturn(List.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userInternalService.updateContactDetails("u-1", "Updated Name", "+94771234567");
        assertEquals("Updated Name", updated.getName());
        assertEquals("+94771234567", updated.getPhone());
    }

    @Test
    @DisplayName("updateContactDetails: Should allow updating phone to equivalent form of own existing phone")
    void shouldAllowKeepingOwnPhoneInEquivalentFormatOnContactUpdate() {
        User user = new User();
        user.setId("u-1");
        user.setPhone("+94771234567");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(userRepository.findByPhoneIn(any())).thenReturn(List.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userInternalService.updateContactDetails("u-1", "Updated Name", "0771234567");
        assertEquals("Updated Name", updated.getName());
        assertEquals("+94771234567", updated.getPhone());
    }

    @Test
    @DisplayName("updateContactDetails: Should reject phone when another user holds an equivalent format")
    void shouldRejectDuplicatePhoneWhenAnotherUserHasEquivalentFormatOnContactUpdate() {
        User user = new User();
        user.setId("u-1");
        user.setPhone("+94711111111");

        User other = new User();
        other.setId("u-2");
        other.setPhone("0771234567");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(userRepository.findByPhoneIn(any())).thenReturn(List.of(other));

        ResourceConflictException ex = assertThrows(
                ResourceConflictException.class,
                () -> userInternalService.updateContactDetails("u-1", "New Name", "+94771234567")
        );
        assertEquals("PHONE_ALREADY_EXISTS", ex.getErrorCode());
        verify(userRepository, never()).save(any());
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
    @DisplayName("changePassword: New password exceeding 72 UTF-8 bytes should be rejected with 400")
    void shouldRejectNewPasswordExceeding72Utf8Bytes() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-old-pass");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "hashed-old-pass")).thenReturn(true);
        String longPassword = "ValidPass1" + "a".repeat(63);
        when(passwordEncoder.matches(longPassword, "hashed-old-pass")).thenReturn(false);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changePassword("u-1", "OldPass123!", longPassword)
        );
        assertEquals("WEAK_PASSWORD", ex.getErrorCode());
        verify(passwordEncoder, never()).encode(longPassword);
    }

    @Test
    @DisplayName("changePassword: Valid historical current password exceeding 72 bytes should be accepted")
    void shouldAllowCurrentPasswordExceeding72BytesWhenValid() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("hashed-old-long-pass");

        String longCurrentPassword = "HistoricalLongPass123!" + "x".repeat(60);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(longCurrentPassword, "hashed-old-long-pass")).thenReturn(true);
        when(passwordEncoder.matches("NewPass123!", "hashed-old-long-pass")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123!")).thenReturn("hashed-new-pass");

        userInternalService.changePassword("u-1", longCurrentPassword, "NewPass123!");

        assertEquals("hashed-new-pass", user.getPasswordHash());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("changePassword: IllegalArgumentException from passwordEncoder.matches should be handled as INVALID_CURRENT_PASSWORD")
    void shouldCatchIllegalArgumentExceptionInPasswordEncoderMatchesAsInvalidCurrentPassword() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("corrupted-hash");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CurrentPass123!", "corrupted-hash"))
                .thenThrow(new IllegalArgumentException("Invalid salt or format"));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.changePassword("u-1", "CurrentPass123!", "NewPass123!")
        );
        assertEquals("INVALID_CURRENT_PASSWORD", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(userRepository, never()).save(any());
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
    @DisplayName("verifyPassword: IllegalArgumentException from passwordEncoder.matches should be handled as INVALID_PASSWORD")
    void shouldCatchIllegalArgumentExceptionInPasswordEncoderMatchesAsInvalidPassword() {
        User user = new User();
        user.setId("u-1");
        user.setPasswordHash("corrupted-hash");

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass123!", "corrupted-hash"))
                .thenThrow(new IllegalArgumentException("Invalid salt or format"));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> userInternalService.verifyPassword("u-1", "Pass123!")
        );
        assertEquals("INVALID_PASSWORD", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
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

    // --- Access control tests for searchUsers and countByRoleAndStatus ---

    @Test
    @DisplayName("searchUsers: No authentication in SecurityContext throws 401 AUTHENTICATION_REQUIRED and performs no data access")
    void searchUsersRejectsUnauthenticated() {
        SecurityContextHolder.clearContext();

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("u-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("searchUsers: Anonymous authentication throws 401 AUTHENTICATION_REQUIRED and performs no data access")
    void searchUsersRejectsAnonymousAuthentication() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("u-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("searchUsers: Non-UserPrincipal authentication throws 401 AUTHENTICATION_REQUIRED and performs no data access")
    void searchUsersRejectsNonAppPrincipal() {
        UsernamePasswordAuthenticationToken nonAppAuth = new UsernamePasswordAuthenticationToken(
                "plain-username", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(nonAppAuth);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("u-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("searchUsers: Mismatched actorId throws 403 ACTOR_IDENTITY_MISMATCH and performs no data access")
    void searchUsersRejectsMismatchedActorId() {
        authenticateAs("u-1", "u1@marketplace.local", Role.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("u-2", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("searchUsers: Privilege spoofing in both directions throws 403 ACTOR_IDENTITY_MISMATCH")
    void searchUsersRejectsPrivilegeSpoofingInBothDirections() {
        // 1. Authenticated ADMIN supplies SUPER_ADMIN id
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        ApiException ex1 = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("super-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex1.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex1.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);

        // 2. Authenticated SUPER_ADMIN supplies ADMIN id
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        ApiException ex2 = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("admin-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex2.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex2.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("searchUsers: Null or blank actorId throws 403 ACTOR_NOT_FOUND")
    void searchUsersRejectsNullOrBlankActorId() {
        authenticateAs("actor-1", "actor1@marketplace.local", Role.ADMIN);

        ApiException ex1 = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers(null, null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_NOT_FOUND", ex1.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex1.getStatus());

        ApiException ex2 = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("  ", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_NOT_FOUND", ex2.getErrorCode());
    }

    @Test
    @DisplayName("searchUsers: Unknown actorId throws 403 ACTOR_NOT_FOUND")
    void searchUsersRejectsUnknownActorId() {
        authenticateAs("unknown", "unknown@marketplace.local", Role.ADMIN);
        when(userRepository.findById("unknown")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("unknown", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("searchUsers: Inactive actor throws 403 ACTOR_ACCOUNT_INACTIVE")
    void searchUsersRejectsInactiveActor() {
        authenticateAs("actor-1", "actor1@marketplace.local", Role.ADMIN);
        User inactive = createUser("actor-1", Role.ADMIN, UserStatus.SUSPENDED);
        when(userRepository.findById("actor-1")).thenReturn(Optional.of(inactive));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("actor-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ACTOR_ACCOUNT_INACTIVE", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "FINANCE_ADMIN", "MODERATOR", "CUSTOMER", "PROVIDER"})
    @DisplayName("searchUsers: Non-permitted actor roles throw 403 ROLE_NOT_PERMITTED")
    void searchUsersRejectsNonPermittedActorRoles(Role role) {
        authenticateAs("actor-1", "actor1@marketplace.local", role);
        User actor = createUser("actor-1", role, UserStatus.ACTIVE);
        when(userRepository.findById("actor-1")).thenReturn(Optional.of(actor));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("actor-1", null, null, null, PageRequest.of(0, 10)));
        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("searchUsers: ADMIN actor explicitly requesting forbidden role throws 403 ROLE_NOT_PERMITTED")
    void searchUsersAdminRequestingForbiddenRoleThrows() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.searchUsers("admin-1", Set.of(Role.CUSTOMER, Role.SUPER_ADMIN), null, null, PageRequest.of(0, 10)));
        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("searchUsers: ADMIN actor unrestricted or requesting visible roles succeeds")
    void searchUsersAdminPermitted() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));
        when(mongoTemplate.count(any(Query.class), eq(User.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(User.class))).thenReturn(List.of());

        assertDoesNotThrow(() -> userInternalService.searchUsers("admin-1", null, null, null, PageRequest.of(0, 10)));
        assertDoesNotThrow(() -> userInternalService.searchUsers("admin-1", Set.of(Role.CUSTOMER), null, null, PageRequest.of(0, 10)));
    }

    @Test
    @DisplayName("searchUsers: SUPER_ADMIN actor can view any role")
    void searchUsersSuperAdminPermitted() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User superAdmin = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("super-1")).thenReturn(Optional.of(superAdmin));
        when(mongoTemplate.count(any(Query.class), eq(User.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(User.class))).thenReturn(List.of());

        assertDoesNotThrow(() -> userInternalService.searchUsers("super-1", null, null, null, PageRequest.of(0, 10)));
        assertDoesNotThrow(() -> userInternalService.searchUsers("super-1", Set.of(Role.SUPER_ADMIN, Role.MODERATOR), null, null, PageRequest.of(0, 10)));
    }

    @Test
    @DisplayName("countByRoleAndStatus: No authentication in SecurityContext throws 401 AUTHENTICATION_REQUIRED and performs no data access")
    void countByRoleAndStatusRejectsUnauthenticated() {
        SecurityContextHolder.clearContext();

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("u-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countByRoleAndStatus: Anonymous authentication throws 401 AUTHENTICATION_REQUIRED and performs no data access")
    void countByRoleAndStatusRejectsAnonymousAuthentication() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("u-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countByRoleAndStatus: Non-UserPrincipal authentication throws 401 AUTHENTICATION_REQUIRED and performs no data access")
    void countByRoleAndStatusRejectsNonAppPrincipal() {
        UsernamePasswordAuthenticationToken nonAppAuth = new UsernamePasswordAuthenticationToken(
                "plain-username", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(nonAppAuth);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("u-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countByRoleAndStatus: Mismatched actorId throws 403 ACTOR_IDENTITY_MISMATCH and performs no data access")
    void countByRoleAndStatusRejectsMismatchedActorId() {
        authenticateAs("u-1", "u1@marketplace.local", Role.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("u-2", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countByRoleAndStatus: Privilege spoofing in both directions throws 403 ACTOR_IDENTITY_MISMATCH")
    void countByRoleAndStatusRejectsPrivilegeSpoofingInBothDirections() {
        // 1. Authenticated ADMIN supplies SUPER_ADMIN id
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        ApiException ex1 = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("super-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex1.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex1.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);

        // 2. Authenticated SUPER_ADMIN supplies ADMIN id
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        ApiException ex2 = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("admin-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex2.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex2.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countByRoleAndStatus: Null or blank actorId throws 403 ACTOR_NOT_FOUND")
    void countByRoleAndStatusRejectsNullOrBlankActorId() {
        authenticateAs("actor-1", "actor1@marketplace.local", Role.ADMIN);

        ApiException ex1 = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus(null, Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_NOT_FOUND", ex1.getErrorCode());

        ApiException ex2 = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus(" ", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_NOT_FOUND", ex2.getErrorCode());
    }

    @Test
    @DisplayName("countByRoleAndStatus: Unknown actorId throws 403 ACTOR_NOT_FOUND")
    void countByRoleAndStatusRejectsUnknownActorId() {
        authenticateAs("unknown", "unknown@marketplace.local", Role.ADMIN);
        when(userRepository.findById("unknown")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("unknown", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("countByRoleAndStatus: Inactive actor throws 403 ACTOR_ACCOUNT_INACTIVE")
    void countByRoleAndStatusRejectsInactiveActor() {
        authenticateAs("actor-1", "actor1@marketplace.local", Role.ADMIN);
        User inactive = createUser("actor-1", Role.ADMIN, UserStatus.LOCKED);
        when(userRepository.findById("actor-1")).thenReturn(Optional.of(inactive));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("actor-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ACTOR_ACCOUNT_INACTIVE", ex.getErrorCode());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "FINANCE_ADMIN", "MODERATOR", "CUSTOMER", "PROVIDER"})
    @DisplayName("countByRoleAndStatus: Non-permitted actor roles throw 403 ROLE_NOT_PERMITTED")
    void countByRoleAndStatusRejectsNonPermittedActorRoles(Role role) {
        authenticateAs("actor-1", "actor1@marketplace.local", role);
        User actor = createUser("actor-1", role, UserStatus.ACTIVE);
        when(userRepository.findById("actor-1")).thenReturn(Optional.of(actor));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("actor-1", Role.CUSTOMER, UserStatus.ACTIVE));
        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("countByRoleAndStatus: ADMIN requesting non-visible role throws 403 ROLE_NOT_PERMITTED")
    void countByRoleAndStatusAdminRequestingForbiddenRoleThrows() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countByRoleAndStatus("admin-1", Role.SUPER_ADMIN, UserStatus.ACTIVE));
        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("countByRoleAndStatus: ADMIN requesting visible role succeeds")
    void countByRoleAndStatusAdminPermitted() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndStatus(Role.CUSTOMER, UserStatus.ACTIVE)).thenReturn(5L);

        long count = userInternalService.countByRoleAndStatus("admin-1", Role.CUSTOMER, UserStatus.ACTIVE);
        assertEquals(5L, count);
    }

    @Test
    @DisplayName("countByRoleAndStatus: SUPER_ADMIN requesting any role succeeds")
    void countByRoleAndStatusSuperAdminPermitted() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User superAdmin = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("super-1")).thenReturn(Optional.of(superAdmin));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);

        long count = userInternalService.countByRoleAndStatus("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        assertEquals(2L, count);
    }

    // --- Tests for countNewUsers ---

    @Test
    @DisplayName("countNewUsers: No authentication in SecurityContext throws 401 AUTHENTICATION_REQUIRED")
    void countNewUsersRejectsUnauthenticated() {
        SecurityContextHolder.clearContext();
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("u-1", null, from, to, Granularity.WEEK));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Anonymous authentication throws 401 AUTHENTICATION_REQUIRED")
    void countNewUsersRejectsAnonymous() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("u-1", null, from, to, Granularity.WEEK));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Non-UserPrincipal throws 401 AUTHENTICATION_REQUIRED")
    void countNewUsersRejectsNonAppPrincipal() {
        UsernamePasswordAuthenticationToken nonAppAuth = new UsernamePasswordAuthenticationToken(
                "plain-username", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(nonAppAuth);
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("u-1", null, from, to, Granularity.WEEK));
        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Mismatched actorId throws 403 ACTOR_IDENTITY_MISMATCH")
    void countNewUsersRejectsMismatchedActorId() {
        authenticateAs("u-1", "u1@marketplace.local", Role.ADMIN);
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("u-2", null, from, to, Granularity.WEEK));
        assertEquals("ACTOR_IDENTITY_MISMATCH", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Null or blank actorId throws 403 ACTOR_NOT_FOUND")
    void countNewUsersRejectsNullOrBlankActorId() {
        authenticateAs("actor-1", "actor1@marketplace.local", Role.ADMIN);
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex1 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers(null, null, from, to, Granularity.WEEK));
        assertEquals("ACTOR_NOT_FOUND", ex1.getErrorCode());

        ApiException ex2 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("  ", null, from, to, Granularity.WEEK));
        assertEquals("ACTOR_NOT_FOUND", ex2.getErrorCode());
    }

    @Test
    @DisplayName("countNewUsers: Unknown actorId throws 403 ACTOR_NOT_FOUND")
    void countNewUsersRejectsUnknownActorId() {
        authenticateAs("unknown", "unknown@marketplace.local", Role.ADMIN);
        when(userRepository.findById("unknown")).thenReturn(Optional.empty());
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("unknown", null, from, to, Granularity.WEEK));
        assertEquals("ACTOR_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("countNewUsers: Inactive actor throws 403 ACTOR_ACCOUNT_INACTIVE")
    void countNewUsersRejectsInactiveActor() {
        authenticateAs("actor-1", "actor1@marketplace.local", Role.ADMIN);
        User inactive = createUser("actor-1", Role.ADMIN, UserStatus.SUSPENDED);
        when(userRepository.findById("actor-1")).thenReturn(Optional.of(inactive));
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("actor-1", null, from, to, Granularity.WEEK));
        assertEquals("ACTOR_ACCOUNT_INACTIVE", ex.getErrorCode());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "FINANCE_ADMIN", "MODERATOR", "CUSTOMER", "PROVIDER"})
    @DisplayName("countNewUsers: Non-permitted actor roles throw 403 ROLE_NOT_PERMITTED")
    void countNewUsersRejectsNonPermittedActorRoles(Role role) {
        authenticateAs("actor-1", "actor1@marketplace.local", role);
        User actor = createUser("actor-1", role, UserStatus.ACTIVE);
        when(userRepository.findById("actor-1")).thenReturn(Optional.of(actor));
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("actor-1", null, from, to, Granularity.WEEK));
        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("countNewUsers: Invalid date ranges or null parameters throw 400")
    void countNewUsersRejectsInvalidParameters() {
        Instant from = Instant.parse("2026-11-20T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");

        // null from
        ApiException ex1 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", null, null, to, Granularity.WEEK));
        assertEquals("INVALID_PARAMETERS", ex1.getErrorCode());

        // null to
        ApiException ex2 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", null, from, null, Granularity.WEEK));
        assertEquals("INVALID_PARAMETERS", ex2.getErrorCode());

        // null granularity
        ApiException ex3 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", null, from, to, null));
        assertEquals("INVALID_PARAMETERS", ex3.getErrorCode());

        // from is after to
        ApiException ex4 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", null, from, to, Granularity.WEEK));
        assertEquals("INVALID_DATE_RANGE", ex4.getErrorCode());

        // from equals to
        ApiException ex5 = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", null, from, from, Granularity.WEEK));
        assertEquals("INVALID_DATE_RANGE", ex5.getErrorCode());
    }

    @Test
    @DisplayName("countNewUsers: ADMIN requesting non-visible role throws 403 ROLE_NOT_PERMITTED")
    void countNewUsersAdminRequestingForbiddenRoleThrows() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", Set.of(Role.SUPER_ADMIN), from, to, Granularity.WEEK));
        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("countNewUsers: Weekly granularity fills empty periods with 0 and orders chronologically")
    void countNewUsersWeeklyFillsEmptyPeriodsWithZero() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User superAdmin = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("super-1")).thenReturn(Optional.of(superAdmin));

        // 2026-11-02 (Monday) to 2026-11-23 (Monday): 3 full weeks (starts: Nov 2, Nov 9, Nov 16)
        Instant from = Instant.parse("2026-11-02T00:00:00Z");
        Instant to = Instant.parse("2026-11-23T00:00:00Z");

        Instant week1 = Instant.parse("2026-11-02T00:00:00Z");
        Instant week3 = Instant.parse("2026-11-16T00:00:00Z");

        // Aggregation returns counts for week 1 (count 5) and week 3 (count 2), week 2 has 0
        List<Document> aggDocs = List.of(
                new Document("_id", Date.from(week1)).append("count", 5L),
                new Document("_id", Date.from(week3)).append("count", 2L)
        );
        AggregationResults<Document> aggResults = new AggregationResults<>(aggDocs, new Document());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(User.class), eq(Document.class)))
                .thenReturn(aggResults);

        List<PeriodCount> results = userInternalService.countNewUsers("super-1", null, from, to, Granularity.WEEK);

        assertNotNull(results);
        assertEquals(3, results.size());

        // Period 1 (Nov 2)
        assertEquals(week1, results.get(0).periodStart());
        assertEquals(5L, results.get(0).count());

        // Period 2 (Nov 9) - empty period filled with 0
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), results.get(1).periodStart());
        assertEquals(0L, results.get(1).count());

        // Period 3 (Nov 16)
        assertEquals(week3, results.get(2).periodStart());
        assertEquals(2L, results.get(2).count());
    }

    @Test
    @DisplayName("countNewUsers: Monthly granularity fills empty months with 0")
    void countNewUsersMonthlyFillsEmptyMonthsWithZero() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        // 2026-08-01 to 2026-11-01: 3 months (Aug, Sep, Oct)
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");

        Instant sepMonth = Instant.parse("2026-09-01T00:00:00Z");

        // Aggregation returns data only for September
        List<Document> aggDocs = List.of(
                new Document("_id", Date.from(sepMonth)).append("count", 12L)
        );
        AggregationResults<Document> aggResults = new AggregationResults<>(aggDocs, new Document());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(User.class), eq(Document.class)))
                .thenReturn(aggResults);

        List<PeriodCount> results = userInternalService.countNewUsers(
                "admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.MONTH);

        assertNotNull(results);
        assertEquals(3, results.size());

        // Aug (empty -> 0)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), results.get(0).periodStart());
        assertEquals(0L, results.get(0).count());

        // Sep (12)
        assertEquals(sepMonth, results.get(1).periodStart());
        assertEquals(12L, results.get(1).count());

        // Oct (empty -> 0)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), results.get(2).periodStart());
        assertEquals(0L, results.get(2).count());
    }

    // --- Tests for atomic changeStatus(actorId, userId, expectedStatus, newStatus) ---

    @Test
    @DisplayName("changeStatus atomic: Successfully changes status to SUSPENDED and revokes refresh tokens")
    void changeStatusAtomicSuccessToSuspended() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);
        User updatedTarget = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1"))
                .thenReturn(Optional.of(target))       // Initial lookup
                .thenReturn(Optional.of(updatedTarget)); // Post-update lookup

        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User result = userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED);

        assertNotNull(result);
        assertEquals(UserStatus.SUSPENDED, result.getStatus());
        // Successful status change to SUSPENDED revokes tokens twice (Step 1 pre-update and Step 3 post-update best-effort)
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);
    }

    @Test
    @DisplayName("changeStatus atomic: Successfully changes status to DEACTIVATED and revokes refresh tokens")
    void changeStatusAtomicSuccessToDeactivated() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User updatedTarget = createUser("admin-1", Role.ADMIN, UserStatus.DEACTIVATED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-1"))
                .thenReturn(Optional.of(target))
                .thenReturn(Optional.of(updatedTarget));

        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User result = userInternalService.changeStatus("super-1", "admin-1", UserStatus.ACTIVE, UserStatus.DEACTIVATED);

        assertNotNull(result);
        assertEquals(UserStatus.DEACTIVATED, result.getStatus());
        // Successful status change to DEACTIVATED revokes tokens twice (Step 1 pre-update and Step 3 post-update best-effort)
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("admin-1", TokenType.REFRESH);
    }

    @Test
    @DisplayName("changeStatus atomic: Successfully changes status to ACTIVE and does NOT revoke tokens")
    void changeStatusAtomicSuccessToActive() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);
        User updatedTarget = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1"))
                .thenReturn(Optional.of(target))
                .thenReturn(Optional.of(updatedTarget));

        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User result = userInternalService.changeStatus("admin-1", "cust-1", UserStatus.SUSPENDED, UserStatus.ACTIVE);

        assertNotNull(result);
        assertEquals(UserStatus.ACTIVE, result.getStatus());
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserIdAndType(anyString(), any(TokenType.class));
    }

    @Test
    @DisplayName("changeStatus atomic: Early rejection when loaded target status differs from expectedStatus")
    void changeStatusEarlyStaleViewRejectionThrows409WithoutRevokingOrUpdating() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        // Target in DB is already SUSPENDED, but caller expected ACTIVE
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(target));

        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.DEACTIVATED));

        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("modified concurrently"));
        // Early rejection must NOT revoke sessions or update status
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserIdAndType(anyString(), any(TokenType.class));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("changeStatus atomic: Early rejection releases SUPER_ADMIN guard when loaded status differs")
    void changeStatusEarlyStaleViewRejectionReleasesSuperAdminGuard() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        // Target SUPER_ADMIN in DB is already LOCKED, but caller expected ACTIVE
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.LOCKED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-stale"));

        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // Guard was acquired, then released in finally block
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq("auth_locks"));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserIdAndType(anyString(), any(TokenType.class));
    }

    @Test
    @DisplayName("changeStatus atomic: Concurrent modification returns 409 CONCURRENT_MODIFICATION")
    void changeStatusAtomicConcurrentModificationThrows409() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(target));

        // 0 documents matched because status was changed concurrently between load and update
        UpdateResult updateResult = UpdateResult.acknowledged(0L, 0L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);
        when(userRepository.existsById("cust-1")).thenReturn(true);

        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("modified concurrently"));
        // In this narrow race, Step 1 already revoked sessions before Step 2 failed to match documents.
        // The user's status was not changed by this call, no second revoke ran, and the user simply has to log in again.
        verify(authTokenRepository, times(1)).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);
    }

    @Test
    @DisplayName("changeStatus atomic: Target user not found throws 404 USER_NOT_FOUND")
    void changeStatusAtomicTargetNotFoundThrows404() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("non-existent")).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> userInternalService.changeStatus("admin-1", "non-existent", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("changeStatus atomic: Actor not found in DB throws 403 ACTOR_NOT_FOUND")
    void changeStatusAtomicActorNotFoundThrows() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        when(userRepository.findById("admin-1")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));
        assertEquals("ACTOR_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("changeStatus atomic: Mismatched actorId throws 403 ACTOR_IDENTITY_MISMATCH")
    void changeStatusAtomicMismatchedActorIdThrows() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-2", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("ACTOR_IDENTITY_MISMATCH", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(userRepository, mongoTemplate);
    }

    @Test
    @DisplayName("changeStatus atomic: Null or blank actorId throws 403 ACTOR_NOT_FOUND")
    void changeStatusAtomicNullOrBlankActorIdThrows() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);

        ApiException exNull = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus(null, "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));
        assertEquals("ACTOR_NOT_FOUND", exNull.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exNull.getStatus());

        ApiException exBlank = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("  ", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));
        assertEquals("ACTOR_NOT_FOUND", exBlank.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exBlank.getStatus());
    }

    @Test
    @DisplayName("changeStatus atomic: Role hierarchy violation throws 403 ROLE_HIERARCHY_VIOLATION")
    void changeStatusAtomicRoleHierarchyViolationThrows() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetAdmin = createUser("admin-2", Role.ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-2")).thenReturn(Optional.of(targetAdmin));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "admin-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("ROLE_HIERARCHY_VIOLATION", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("changeStatus atomic: Self modification throws 403 CANNOT_MODIFY_SELF")
    void changeStatusAtomicSelfModificationThrows() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid"));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("CANNOT_MODIFY_SELF", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("changeStatus atomic: Target DEACTIVATED throws 403 ACCOUNT_DEACTIVATED")
    void changeStatusAtomicTargetDeactivatedThrows() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.DEACTIVATED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(target));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.DEACTIVATED, UserStatus.ACTIVE));

        assertEquals("ACCOUNT_DEACTIVATED", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("changeStatus atomic: Last remaining ACTIVE SUPER_ADMIN throws 409 LAST_ACTIVE_SUPER_ADMIN")
    void changeStatusAtomicLastActiveSuperAdminThrows() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(targetSuper));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid"));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("LAST_ACTIVE_SUPER_ADMIN", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    // --- F1 Legacy 2-argument changeStatus delegation tests ---

    @Test
    @DisplayName("legacy changeStatus: Successfully revokes refresh tokens when changing to SUSPENDED")
    void legacyChangeStatusRevokesTokensOnSuspended() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User targetCust = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);
        User updatedCust = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1"))
                .thenReturn(Optional.of(targetCust))
                .thenReturn(Optional.of(targetCust))
                .thenReturn(Optional.of(updatedCust));
        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User updated = userInternalService.changeStatus("cust-1", UserStatus.SUSPENDED);

        assertNotNull(updated);
        assertEquals(UserStatus.SUSPENDED, updated.getStatus());
        // Successful status change to SUSPENDED revokes tokens twice (Step 1 pre-update and Step 3 post-update best-effort)
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);
    }

    @Test
    @DisplayName("legacy changeStatus: Successfully revokes refresh tokens when changing to DEACTIVATED")
    void legacyChangeStatusRevokesTokensOnDeactivated() {
        authenticateAs("super-1", "super@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetAdmin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User updatedAdmin = createUser("admin-1", Role.ADMIN, UserStatus.DEACTIVATED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("admin-1"))
                .thenReturn(Optional.of(targetAdmin))
                .thenReturn(Optional.of(targetAdmin))
                .thenReturn(Optional.of(updatedAdmin));
        UpdateResult updateResult = UpdateResult.acknowledged(1L, 1L, null);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(updateResult);

        User updated = userInternalService.changeStatus("admin-1", UserStatus.DEACTIVATED);

        assertNotNull(updated);
        assertEquals(UserStatus.DEACTIVATED, updated.getStatus());
        // Successful status change to DEACTIVATED revokes tokens twice (Step 1 pre-update and Step 3 post-update best-effort)
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("admin-1", TokenType.REFRESH);
    }

    @Test
    @DisplayName("legacy changeStatus: Enforces last active SUPER_ADMIN protection")
    void legacyChangeStatusEnforcesLastActiveSuperAdmin() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User targetSuper = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(targetSuper));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid"));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-2", UserStatus.SUSPENDED));

        assertEquals("LAST_ACTIVE_SUPER_ADMIN", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("legacy changeStatus: Unknown target user throws 404 USER_NOT_FOUND")
    void legacyChangeStatusTargetNotFoundThrows404() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        when(userRepository.findById("unknown-user")).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> userInternalService.changeStatus("unknown-user", UserStatus.SUSPENDED));

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    // --- Guard and Concurrency-Safe Last-Active SUPER_ADMIN tests ---

    @Test
    @DisplayName("changeStatus Guard: Guard busy throws 409 CONCURRENT_MODIFICATION without updating status or revoking tokens")
    void changeStatusGuardBusyThrows409ConcurrentModification() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        // findAndModify returns null across all retries (guard busy)
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(null);

        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("currently locked"));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserIdAndType(anyString(), any(TokenType.class));
    }

    @Test
    @DisplayName("changeStatus Guard: Guard is released after successful status update")
    void changeStatusGuardReleasedAfterSuccess() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User updated = createUser("super-2", Role.SUPER_ADMIN, UserStatus.SUSPENDED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2"))
                .thenReturn(Optional.of(target))
                .thenReturn(Optional.of(updated));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-success").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        User result = userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED);

        assertNotNull(result);
        assertEquals(UserStatus.SUSPENDED, result.getStatus());
        // Verify transaction was committed
        verify(transactionManager).commit(any());
        // Verify guard renewal and release executed on auth_locks
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(Query.class), any(Update.class), eq("auth_locks"));
    }

    @Test
    @DisplayName("changeStatus Guard: Guard is released when AdminRolePolicy rejects change (e.g. last SUPER_ADMIN)")
    void changeStatusGuardReleasedAfterPolicyRejection() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-policy").append("fence", 1L));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("LAST_ACTIVE_SUPER_ADMIN", ex.getErrorCode());
        // Verify guard release was executed on auth_locks
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq("auth_locks"));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("changeStatus Guard: Guard is released when status update throws exception")
    void changeStatusGuardReleasedAfterUpdateException() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-ex").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenThrow(new RuntimeException("Mongo connection dropped"));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        // Verify transaction rolled back
        verify(transactionManager).rollback(any());
        // Verify guard renewal and release executed on auth_locks
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(Query.class), any(Update.class), eq("auth_locks"));
    }

    @Test
    @DisplayName("changeStatus Guard: Proof failure before Step 1 throws 503 without token revocation or user update")
    void changeStatusGuardProofFailsBeforeStep1Throws503() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-p1").append("fence", 1L));
        // Proof point (b) fails: updateFirst on auth_locks matches 0 documents (lease expired/overtaken)
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("The status change could not be completed safely. No status was changed. Please reload and retry.", ex.getMessage());
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserIdAndType(anyString(), any(TokenType.class));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("changeStatus Guard: Proof failure inside transaction rolls back and throws 503 without user update")
    void changeStatusGuardProofFailsBeforeStep2Throws503() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-p2").append("fence", 1L));
        // Proof (b) succeeds, but proof inside transaction matches 0
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("The status change could not be completed safely. No status was changed. Please reload and retry.", ex.getMessage());
        // Step 1 token revoke executed once before transaction proof failed
        verify(authTokenRepository, times(1)).revokeAllActiveTokensByUserIdAndType("super-2", TokenType.REFRESH);
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("changeStatus Guard: Policy recheck inside transaction finds one active SUPER_ADMIN rolls back and throws 409")
    void changeStatusPolicyRecheckBeforeStep2FindsOneActiveSuperAdminThrows409() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        // Initial policy check passes (2 active), recheck inside transaction finds 1 active
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE))
                .thenReturn(2L)
                .thenReturn(1L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-recheck").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("LAST_ACTIVE_SUPER_ADMIN", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // Step 1 revoked tokens once
        verify(authTokenRepository, times(1)).revokeAllActiveTokensByUserIdAndType("super-2", TokenType.REFRESH);
        // Step 2 DB update was never executed
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("changeStatus Guard: Local time bound exceeded before Step 2 throws 503")
    void changeStatusLocalTimeBoundExceededBeforeStep2Throws503() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-timeout").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        // Configure safety margin >= lease so maxAllowedNanos <= 0, causing time bound check to fail immediately
        userInternalService.setSuperAdminLockLeaseMs(10000L);
        userInternalService.setSuperAdminLockSafetyMarginMs(10000L);
        try {
            ApiException ex = assertThrows(ApiException.class,
                    () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

            assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertEquals("The status change could not be completed safely. No status was changed. Please reload and retry.", ex.getMessage());
            verify(authTokenRepository, times(1)).revokeAllActiveTokensByUserIdAndType("super-2", TokenType.REFRESH);
            verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
        } finally {
            userInternalService.setSuperAdminLockSafetyMarginMs(3000L);
        }
    }

    @Test
    @DisplayName("changeStatus Guard: User update failure inside transaction rolls back and throws neutral 503")
    void changeStatusUserUpdateThrowsInsideTransactionRollsBackAndThrowsNeutral503() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-ex").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenThrow(new RuntimeException("Mongo connection dropped"));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("The status change could not be confirmed. Please reload the user and retry.", ex.getMessage());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("changeStatus Guard: Transient transaction or write conflict rolls back and throws 409")
    void changeStatusTransientTransactionErrorRollsBackAndThrows409() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-conflict").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        MongoException conflictEx = new MongoException(112, "WriteConflict");
        conflictEx.addLabel("TransientTransactionError");
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenThrow(conflictEx);

        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("changeStatus Guard: Standalone Mongo transactions not supported fails closed with 503 without fallback")
    void changeStatusTransactionsNotSupportedFailsClosedThrows503WithoutFallback() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-not-supported").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        MongoException notSupportedEx = new MongoException(20,
                "Transaction numbers are only allowed on a replica set member or mongos");
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenThrow(notSupportedEx);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("The status change could not be completed safely. No status was changed. Please reload and retry.", ex.getMessage());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("changeStatus Guard: Successful SUPER_ADMIN status update commits transaction and executes secondary revocation")
    void changeStatusSuperAdminSuccessCommitsTransactionAndRevokesTokensAfterCommit() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User updated = createUser("super-2", Role.SUPER_ADMIN, UserStatus.SUSPENDED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2"))
                .thenReturn(Optional.of(target))
                .thenReturn(Optional.of(updated));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid-success").append("fence", 1L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        User result = userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED);

        assertNotNull(result);
        assertEquals(UserStatus.SUSPENDED, result.getStatus());
        verify(transactionManager).commit(any());
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("super-2", TokenType.REFRESH);
    }

    @Test
    @DisplayName("changeStatus Guard: Guard released in finally with matching owner and fence")
    void changeStatusGuardReleasedInFinallyWithMatchingOwnerAndFence() {
        authenticateAs("super-1", "super1@marketplace.local", Role.SUPER_ADMIN);
        User actor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User target = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User updated = createUser("super-2", Role.SUPER_ADMIN, UserStatus.SUSPENDED);

        when(userRepository.findById("super-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("super-2"))
                .thenReturn(Optional.of(target))
                .thenReturn(Optional.of(updated));
        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(2L);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "lock-uuid").append("fence", 42L));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq("auth_locks")))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        userInternalService.changeStatus("super-1", "super-2", UserStatus.ACTIVE, UserStatus.SUSPENDED);

        // 1. Capture the owner the service actually used when acquiring the guard in findAndModify
        ArgumentCaptor<Update> acquireUpdateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(), acquireUpdateCaptor.capture(), any(), eq(Document.class), eq("auth_locks"));
        Document setDoc = (Document) acquireUpdateCaptor.getValue().getUpdateObject().get("$set");
        String acquiredOwner = setDoc.getString("owner");

        // 2. Assert the owner is a valid UUID
        assertNotNull(acquiredOwner);
        assertDoesNotThrow(() -> UUID.fromString(acquiredOwner));

        // 3. Capture the release query (last updateFirst on auth_locks) and verify matching _id, owner, and fence
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(queryCaptor.capture(), any(Update.class), eq("auth_locks"));

        List<Query> capturedQueries = queryCaptor.getAllValues();
        Query releaseQuery = capturedQueries.get(capturedQueries.size() - 1);
        Document queryObject = releaseQuery.getQueryObject();
        assertEquals("super-admin-status", queryObject.get("_id"));
        assertEquals(acquiredOwner, queryObject.get("owner"));
        assertEquals(42L, queryObject.get("fence"));
    }

    @Test
    @DisplayName("F2-B: acquireSuperAdminGuard builds query ignoring legacy lockedUntil if expiresAt exists and unsets lockedUntil in update")
    void acquireSuperAdminGuardBuildsCorrectQueryAndUnsetsLockedUntil() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(Document.class), eq("auth_locks")))
                .thenReturn(new Document("owner", "new-owner").append("fence", 1L));

        userInternalService.acquireSuperAdminGuard();

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(queryCaptor.capture(), updateCaptor.capture(), any(), eq(Document.class), eq("auth_locks"));

        Query query = queryCaptor.getValue();
        Document queryObject = query.getQueryObject();
        assertEquals("super-admin-status", queryObject.get("_id"));
        assertTrue(queryObject.containsKey("$or"));

        Update update = updateCaptor.getValue();
        Document updateObject = update.getUpdateObject();
        assertTrue(updateObject.containsKey("$set"));
        assertTrue(updateObject.containsKey("$unset"));
        assertTrue(updateObject.containsKey("$inc"));
        Document unsetDoc = (Document) updateObject.get("$unset");
        assertTrue(unsetDoc.containsKey("lockedUntil"));
    }

    // --- F3 Status update and token revocation consistency tests (No rollback) ---

    @Test
    @DisplayName("changeStatus F3: Step 1 token revocation failure throws 503 without touching user status")
    void changeStatusRevocationFailureThrows503WithoutTouchingStatus() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(target));

        doThrow(new RuntimeException("Token service down"))
                .when(authTokenRepository).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("The status was not changed because sessions could not be revoked. Please retry.", ex.getMessage());
        // Verify status was never touched in database
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("changeStatus F3: Step 2 status update failure throws 503 with neutral message and no rollback")
    void changeStatusUpdateFailureThrows503WithNeutralMessage() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1")).thenReturn(Optional.of(target));

        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenThrow(new RuntimeException("DB communication error during status write"));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("The status change could not be confirmed. Please reload the user and retry.", ex.getMessage());
        // Verify exactly one update attempt on User (no compensation update)
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    @DisplayName("changeStatus F3: Step 3 best-effort token revocation failure does not fail the request")
    void changeStatusStep3BestEffortRevocationFailureDoesNotFailRequest() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        User target = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);
        User updatedTarget = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);

        when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
        when(userRepository.findById("cust-1"))
                .thenReturn(Optional.of(target))
                .thenReturn(Optional.of(updatedTarget));

        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        // Step 1 succeeds, Step 3 throws
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("mongodb://user:secret@host transient failure"))
                .when(authTokenRepository).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);

        User result = userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED);

        assertNotNull(result);
        assertEquals(UserStatus.SUSPENDED, result.getStatus());
        verify(authTokenRepository, times(2)).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);
    }

    @Test
    @DisplayName("changeStatus logging: Step 3 failure logs only userId and exception simple name, never raw message or secret")
    void changeStatusStep3FailureLogsSafelyWithoutSecrets() {
        ch.qos.logback.classic.Logger serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UserInternalServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender = new ch.qos.logback.core.read.ListAppender<>();
        listAppender.start();
        serviceLogger.addAppender(listAppender);

        try {
            authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
            User actor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
            User target = createUser("cust-1", Role.CUSTOMER, UserStatus.ACTIVE);
            User updatedTarget = createUser("cust-1", Role.CUSTOMER, UserStatus.SUSPENDED);

            when(userRepository.findById("admin-1")).thenReturn(Optional.of(actor));
            when(userRepository.findById("cust-1"))
                    .thenReturn(Optional.of(target))
                    .thenReturn(Optional.of(updatedTarget));

            when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                    .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

            String fakeSecret = "mongodb://user:super_secret_password@host:27017";
            org.mockito.Mockito.doNothing()
                    .doThrow(new RuntimeException(fakeSecret))
                    .when(authTokenRepository).revokeAllActiveTokensByUserIdAndType("cust-1", TokenType.REFRESH);

            User result = userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED);
            assertNotNull(result);

            boolean foundWarning = false;
            for (ch.qos.logback.classic.spi.ILoggingEvent event : listAppender.list) {
                String formatted = event.getFormattedMessage();
                assertFalse(formatted.contains(fakeSecret), "Log message must not contain secret");
                assertFalse(formatted.contains("super_secret_password"), "Log message must not contain raw credentials");
                assertNull(event.getThrowableProxy(), "Throwable proxy must not be attached to log event");
                if (formatted.contains("Failed secondary token revocation for user cust-1: RuntimeException")) {
                    foundWarning = true;
                }
            }
            assertTrue(foundWarning, "Expected warning log with userId and RuntimeException class name");
        } finally {
            serviceLogger.detachAppender(listAppender);
        }
    }

    // --- F4 Actor identity validation tests ---

    @Test
    @DisplayName("changeStatus F4: Missing SecurityContext throws 401 AUTHENTICATION_REQUIRED")
    void changeStatusMissingSecurityContextThrows401() {
        SecurityContextHolder.clearContext();

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("changeStatus F4: Unauthenticated SecurityContext throws 401 AUTHENTICATION_REQUIRED")
    void changeStatusUnauthenticatedSecurityContextThrows401() {
        UsernamePasswordAuthenticationToken unauth = new UsernamePasswordAuthenticationToken("admin-1", null);
        unauth.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauth);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("changeStatus F4: Anonymous authentication throws 401 AUTHENTICATION_REQUIRED")
    void changeStatusAnonymousAuthenticationThrows401() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("changeStatus F4: Non-UserPrincipal authentication throws 401 AUTHENTICATION_REQUIRED")
    void changeStatusNonUserPrincipalThrows401() {
        UsernamePasswordAuthenticationToken nonUserPrincipal =
                new UsernamePasswordAuthenticationToken("just-a-string-principal", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(nonUserPrincipal);

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("AUTHENTICATION_REQUIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("changeStatus F4: Inactive actor in database throws 403 ACTOR_ACCOUNT_INACTIVE")
    void changeStatusInactiveActorThrows403() {
        authenticateAs("admin-1", "admin@marketplace.local", Role.ADMIN);
        User inactiveActor = createUser("admin-1", Role.ADMIN, UserStatus.SUSPENDED);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(inactiveActor));

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.changeStatus("admin-1", "cust-1", UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertEquals("ACTOR_ACCOUNT_INACTIVE", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // --- EXTRA 1 countNewUsers maximum range (120 buckets) tests ---

    @Test
    @DisplayName("countNewUsers: Exactly 120 weekly buckets succeeds")
    void countNewUsersAtMax120BucketsWeekSucceeds() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        Instant from = Instant.parse("2024-01-01T00:00:00Z"); // Monday
        Instant to = from.plus(Duration.ofDays(120 * 7)); // Exactly 120 weeks

        AggregationResults<Document> aggResults = new AggregationResults<>(List.of(), new Document());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(User.class), eq(Document.class)))
                .thenReturn(aggResults);

        List<PeriodCount> results = userInternalService.countNewUsers(
                "admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.WEEK);

        assertNotNull(results);
        assertEquals(120, results.size());
    }

    @Test
    @DisplayName("countNewUsers: 121 weekly buckets throws 400 INVALID_DATE_RANGE")
    void countNewUsersExceeding120BucketsWeekThrows400() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        Instant from = Instant.parse("2024-01-01T00:00:00Z"); // Monday
        Instant to = from.plus(Duration.ofDays(121 * 7)); // 121 weeks

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.WEEK));

        assertEquals("INVALID_DATE_RANGE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("120"));
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Exactly 120 monthly buckets succeeds")
    void countNewUsersAtMax120BucketsMonthSucceeds() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        Instant from = Instant.parse("2010-01-01T00:00:00Z");
        Instant to = Instant.parse("2020-01-01T00:00:00Z"); // Exactly 120 months (10 years)

        AggregationResults<Document> aggResults = new AggregationResults<>(List.of(), new Document());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(User.class), eq(Document.class)))
                .thenReturn(aggResults);

        List<PeriodCount> results = userInternalService.countNewUsers(
                "admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.MONTH);

        assertNotNull(results);
        assertEquals(120, results.size());
    }

    @Test
    @DisplayName("countNewUsers: 121 monthly buckets throws 400 INVALID_DATE_RANGE")
    void countNewUsersExceeding120BucketsMonthThrows400() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        Instant from = Instant.parse("2010-01-01T00:00:00Z");
        Instant to = Instant.parse("2020-02-01T00:00:00Z"); // 121 months

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.MONTH));

        assertEquals("INVALID_DATE_RANGE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("120"));
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Extreme date range (1970 to 9999) throws 400 within 1 second without DB aggregation")
    void countNewUsersExtremeRange1970To9999Throws400Quickly() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        Instant from = Instant.EPOCH;
        Instant to = Instant.parse("9999-12-31T23:59:59Z");

        assertTimeout(Duration.ofSeconds(1), () -> {
            ApiException exWeek = assertThrows(ApiException.class,
                    () -> userInternalService.countNewUsers("admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.WEEK));
            assertEquals("INVALID_DATE_RANGE", exWeek.getErrorCode());
            assertEquals(HttpStatus.BAD_REQUEST, exWeek.getStatus());

            ApiException exMonth = assertThrows(ApiException.class,
                    () -> userInternalService.countNewUsers("admin-1", Set.of(Role.CUSTOMER), from, to, Granularity.MONTH));
            assertEquals("INVALID_DATE_RANGE", exMonth.getErrorCode());
            assertEquals(HttpStatus.BAD_REQUEST, exMonth.getStatus());
        });

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("countNewUsers: Extreme date range (Instant.MIN to Instant.MAX) throws 400 within 1 second without DB aggregation")
    void countNewUsersExtremeRangeMinMaxThrows400Quickly() {
        authenticateAs("admin-1", "admin1@marketplace.local", Role.ADMIN);
        User admin = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        assertTimeout(Duration.ofSeconds(1), () -> {
            ApiException ex = assertThrows(ApiException.class,
                    () -> userInternalService.countNewUsers("admin-1", Set.of(Role.CUSTOMER), Instant.MIN, Instant.MAX, Granularity.WEEK));
            assertEquals("INVALID_DATE_RANGE", ex.getErrorCode());
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        });

        verifyNoInteractions(mongoTemplate);
    }
}
