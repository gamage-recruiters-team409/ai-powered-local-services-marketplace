package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.gamagerecruiters.marketplace.common.exception.AccountStatusException;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LogoutRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterResponse;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthTokenRepository authTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userRepository,
                authTokenRepository,
                passwordEncoder,
                tokenService,
                false, // requireEmailVerification = false for dev
                5,     // maxFailedLogins = 5
                15,    // lockDurationMinutes = 15
                10     // refreshReuseGraceSeconds = 10
        );
    }

    @Test
    @DisplayName("Issue 4: Should register customer with ACTIVE status and emailVerified=false when bypass is enabled")
    void shouldRegisterCustomerWithActiveStatusAndEmailUnverified() {
        RegisterRequest request = new RegisterRequest(
                "Jane Doe",
                "jane@example.com",
                "+12345678901",
                "SecurePass123",
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("+12345678901")).thenReturn(false);
        when(passwordEncoder.encode("SecurePass123")).thenReturn("hashedPass");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });

        RegisterResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("user-1", response.id());
        assertEquals("jane@example.com", response.email());
        assertEquals(Role.CUSTOMER, response.role());
        assertEquals(UserStatus.ACTIVE, response.status());

        // Verify emailVerified is false
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> !u.isEmailVerified()));
    }

    @Test
    @DisplayName("Issue 4: Should register with PENDING_VERIFICATION and emailVerified=false when verification is required")
    void shouldRegisterWithPendingVerificationWhenRequired() {
        AuthServiceImpl serviceWithVerification = new AuthServiceImpl(
                userRepository,
                authTokenRepository,
                passwordEncoder,
                tokenService,
                true, // requireEmailVerification = true
                5,
                15,
                10
        );

        RegisterRequest request = new RegisterRequest(
                "Jane Doe",
                "jane@example.com",
                "+12345678901",
                "SecurePass123",
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("+12345678901")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId("user-1");
            return u;
        });

        RegisterResponse response = serviceWithVerification.register(request);
        assertEquals(UserStatus.PENDING_VERIFICATION, response.status());
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> !u.isEmailVerified()));
    }

    @Test
    @DisplayName("Should reject self-registration with admin roles")
    void shouldRejectAdminRoleRegistration() {
        RegisterRequest request = new RegisterRequest(
                "Admin Wannabe",
                "admin@example.com",
                "+12345678901",
                "SecurePass123",
                Role.ADMIN,
                true,
                "v1.0"
        );

        ApiException ex = assertThrows(ApiException.class, () -> authService.register(request));
        assertEquals("INVALID_ROLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Should reject registration if email is duplicate")
    void shouldRejectDuplicateEmail() {
        RegisterRequest request = new RegisterRequest(
                "Jane Doe",
                "jane@example.com",
                "+12345678901",
                "SecurePass123",
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        when(userRepository.existsByEmail("jane@example.com")).thenReturn(true);

        assertThrows(ResourceConflictException.class, () -> authService.register(request));
    }

    @Test
    @DisplayName("Should reject registration if phone is duplicate")
    void shouldRejectDuplicatePhone() {
        RegisterRequest request = new RegisterRequest(
                "Jane Doe",
                "jane@example.com",
                "+12345678901",
                "SecurePass123",
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("+12345678901")).thenReturn(true);

        assertThrows(ResourceConflictException.class, () -> authService.register(request));
    }

    @Test
    @DisplayName("Should login successfully with active credentials")
    void shouldLoginSuccessfully() {
        LoginRequest request = new LoginRequest("user@marketplace.local", "CorrectPass1");

        User user = new User();
        user.setId("u-1");
        user.setName("John");
        user.setEmail("user@marketplace.local");
        user.setPasswordHash("hashedCorrect");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);

        when(userRepository.findByEmail("user@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPass1", "hashedCorrect")).thenReturn(true);
        when(tokenService.generateAccessToken("u-1", "user@marketplace.local", Role.CUSTOMER)).thenReturn("jwt.token.here");
        when(tokenService.generateRefreshToken()).thenReturn("opaque-refresh-token");
        when(tokenService.hashToken("opaque-refresh-token")).thenReturn("hash-123");
        when(tokenService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(tokenService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("jwt.token.here", response.accessToken());
        assertEquals("opaque-refresh-token", response.refreshToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(900L, response.expiresIn());
        assertEquals("u-1", response.user().id());
        assertEquals("user@marketplace.local", response.user().email());
    }

    @Test
    @DisplayName("Should return generic 401 error message when user is not found")
    void shouldReturnGenericErrorOnUserNotFound() {
        LoginRequest request = new LoginRequest("unknown@marketplace.local", "SomePass123");

        when(userRepository.findByEmail("unknown@marketplace.local")).thenReturn(Optional.empty());

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () -> authService.login(request));
        assertEquals("Invalid email or password", ex.getMessage());
    }

    @Test
    @DisplayName("Should increment failed login count and lock active account after 5 failed attempts")
    void shouldLockAccountAfter5FailedLogins() {
        LoginRequest request = new LoginRequest("user@marketplace.local", "WrongPass");

        User user = new User();
        user.setId("u-1");
        user.setEmail("user@marketplace.local");
        user.setPasswordHash("hashedPass");
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedLoginCount(4); // 4 prior failures

        when(userRepository.findByEmail("user@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "hashedPass")).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        assertEquals(5, user.getFailedLoginCount());
        assertEquals(UserStatus.LOCKED, user.getStatus());
        assertNotNull(user.getLockStartedAt());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("Issue 1: Suspended user with 5+ wrong passwords stays SUSPENDED, counter unchanged, generic 401")
    void shouldKeepSuspendedStatusOnWrongPassword() {
        LoginRequest request = new LoginRequest("suspended@marketplace.local", "WrongPass");

        User user = new User();
        user.setId("u-susp");
        user.setEmail("suspended@marketplace.local");
        user.setPasswordHash("hashedPass");
        user.setStatus(UserStatus.SUSPENDED);
        user.setFailedLoginCount(0);

        when(userRepository.findByEmail("suspended@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "hashedPass")).thenReturn(false);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () -> authService.login(request));
        assertEquals("Invalid email or password", ex.getMessage());

        assertEquals(UserStatus.SUSPENDED, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        assertNull(user.getLockStartedAt());
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("Issue 1: Deactivated user with 5+ wrong passwords stays DEACTIVATED, counter unchanged, generic 401")
    void shouldKeepDeactivatedStatusOnWrongPassword() {
        LoginRequest request = new LoginRequest("deact@marketplace.local", "WrongPass");

        User user = new User();
        user.setId("u-deact");
        user.setEmail("deact@marketplace.local");
        user.setPasswordHash("hashedPass");
        user.setStatus(UserStatus.DEACTIVATED);
        user.setFailedLoginCount(0);

        when(userRepository.findByEmail("deact@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "hashedPass")).thenReturn(false);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () -> authService.login(request));
        assertEquals("Invalid email or password", ex.getMessage());

        assertEquals(UserStatus.DEACTIVATED, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("Issue 1: Pending verification user with wrong password stays PENDING_VERIFICATION, generic 401")
    void shouldKeepPendingVerificationStatusOnWrongPassword() {
        LoginRequest request = new LoginRequest("pending@marketplace.local", "WrongPass");

        User user = new User();
        user.setId("u-pend");
        user.setEmail("pending@marketplace.local");
        user.setPasswordHash("hashedPass");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setFailedLoginCount(0);

        when(userRepository.findByEmail("pending@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "hashedPass")).thenReturn(false);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () -> authService.login(request));
        assertEquals("Invalid email or password", ex.getMessage());

        assertEquals(UserStatus.PENDING_VERIFICATION, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("Issue 1: Suspended account is NEVER reactivated by unlock logic")
    void suspendedAccountNeverReactivatedByUnlockLogic() {
        LoginRequest request = new LoginRequest("suspended@marketplace.local", "CorrectPass");

        User user = new User();
        user.setId("u-susp");
        user.setEmail("suspended@marketplace.local");
        user.setPasswordHash("hashedPass");
        user.setStatus(UserStatus.SUSPENDED);
        user.setLockStartedAt(Instant.now().minus(30, ChronoUnit.MINUTES)); // Lock timestamp in past

        when(userRepository.findByEmail("suspended@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPass", "hashedPass")).thenReturn(true);

        AccountStatusException ex = assertThrows(AccountStatusException.class, () -> authService.login(request));
        assertEquals("ACCOUNT_SUSPENDED", ex.getErrorCode());
        assertEquals(UserStatus.SUSPENDED, user.getStatus());
    }

    @Test
    @DisplayName("Should auto-unlock LOCKED account after 15 minutes duration")
    void shouldAutoUnlockAccountAfter15Minutes() {
        LoginRequest request = new LoginRequest("user@marketplace.local", "CorrectPass");

        User user = new User();
        user.setId("u-1");
        user.setName("John");
        user.setEmail("user@marketplace.local");
        user.setPasswordHash("hashedPass");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.LOCKED);
        user.setFailedLoginCount(5);
        user.setLockStartedAt(Instant.now().minus(16, ChronoUnit.MINUTES)); // Locked 16 mins ago

        when(userRepository.findByEmail("user@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPass", "hashedPass")).thenReturn(true);
        when(tokenService.generateAccessToken("u-1", "user@marketplace.local", Role.CUSTOMER)).thenReturn("jwt.token");
        when(tokenService.generateRefreshToken()).thenReturn("ref-tok");
        when(tokenService.hashToken("ref-tok")).thenReturn("hash");
        when(tokenService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(tokenService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
    }

    @Test
    @DisplayName("Should reject login with 403 when email is not verified and password is correct")
    void shouldRejectLoginWhenPendingVerification() {
        LoginRequest request = new LoginRequest("user@marketplace.local", "CorrectPass");

        User user = new User();
        user.setEmail("user@marketplace.local");
        user.setPasswordHash("hashed");
        user.setStatus(UserStatus.PENDING_VERIFICATION);

        when(userRepository.findByEmail("user@marketplace.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPass", "hashed")).thenReturn(true);

        AccountStatusException ex = assertThrows(AccountStatusException.class, () -> authService.login(request));
        assertEquals("EMAIL_NOT_VERIFIED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Issue 3: Concurrent refresh calls on same token - exactly 1 succeeds and N-1 fail")
    void shouldAllowOnlyOneConcurrentRefreshCall() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        RefreshTokenRequest request = new RefreshTokenRequest("concurrent-refresh-token");
        String tokenHash = "concurrent-token-hash";

        AuthToken validToken = new AuthToken("u-1", TokenType.REFRESH, tokenHash, Instant.now().plusSeconds(3600), Instant.now());
        User user = new User();
        user.setId("u-1");
        user.setName("Concurrent User");
        user.setEmail("concurrent@test.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);

        when(tokenService.hashToken("concurrent-refresh-token")).thenReturn(tokenHash);

        // Atomic simulate: only the first call succeeds via findAndConsumeValidToken
        AtomicBoolean consumed = new AtomicBoolean(false);
        when(authTokenRepository.findAndConsumeValidToken(eq(tokenHash), any(Instant.class)))
                .thenAnswer(inv -> {
                    if (consumed.compareAndSet(false, true)) {
                        return Optional.of(validToken);
                    }
                    return Optional.empty();
                });

        // For the failed attempts, findByTokenHash returns the now-used token (triggering TOKEN_REUSED)
        AuthToken usedTokenRecord = new AuthToken("u-1", TokenType.REFRESH, tokenHash, Instant.now().plusSeconds(3600), Instant.now());
        usedTokenRecord.setUsedAt(Instant.now());
        when(authTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(usedTokenRecord));

        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(tokenService.generateAccessToken("u-1", "concurrent@test.local", Role.CUSTOMER)).thenReturn("jwt.token");
        when(tokenService.generateRefreshToken()).thenReturn("new.refresh.token");
        when(tokenService.hashToken("new.refresh.token")).thenReturn("new.hash");
        when(tokenService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(tokenService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    authService.refresh(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly 1 concurrent refresh request must succeed");
        assertEquals(threadCount - 1, failCount.get(), "N-1 concurrent refresh requests must fail");
        // Verify all failures were ApiException
        assertTrue(exceptions.stream().allMatch(e -> e instanceof ApiException));
    }

    @Test
    @DisplayName("Issue D: Reused refresh token within grace period returns 409 Conflict without revoking user tokens")
    void shouldReturnConflictWithoutRevokingWhenRefreshReusedWithinGracePeriod() {
        RefreshTokenRequest request = new RefreshTokenRequest("recently-used-token");

        AuthToken token = new AuthToken("u-1", TokenType.REFRESH, "hash-recent", Instant.now().plusSeconds(3600), Instant.now());
        // Used 2 seconds ago (within 10s grace period)
        token.setUsedAt(Instant.now().minusSeconds(2));

        when(tokenService.hashToken("recently-used-token")).thenReturn("hash-recent");
        when(authTokenRepository.findAndConsumeValidToken(eq("hash-recent"), any(Instant.class))).thenReturn(Optional.empty());
        when(authTokenRepository.findByTokenHash("hash-recent")).thenReturn(Optional.of(token));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(request));
        assertEquals("REFRESH_IN_PROGRESS", ex.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());

        // Verify tokens are NOT revoked
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserId(anyString());
    }

    @Test
    @DisplayName("Issue D: Reused refresh token after grace period is rejected and revokes all active refresh tokens for the user")
    void shouldRejectReusedRefreshTokenAndRevokeUserTokens() {
        RefreshTokenRequest request = new RefreshTokenRequest("already-used-token");

        AuthToken token = new AuthToken("u-1", TokenType.REFRESH, "hash-used", Instant.now().plusSeconds(3600), Instant.now());
        // Used 30 seconds ago (outside 10s grace period)
        token.setUsedAt(Instant.now().minusSeconds(30));

        when(tokenService.hashToken("already-used-token")).thenReturn("hash-used");
        when(authTokenRepository.findAndConsumeValidToken(eq("hash-used"), any(Instant.class))).thenReturn(Optional.empty());
        when(authTokenRepository.findByTokenHash("hash-used")).thenReturn(Optional.of(token));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(request));
        assertEquals("TOKEN_REUSED", ex.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getStatus());

        // Verify security measure: all active tokens for this user are revoked
        verify(authTokenRepository).revokeAllActiveTokensByUserId("u-1");
    }

    @Test
    @DisplayName("Issue 5: User A sending User B's refresh token on logout does not revoke User B's token")
    void shouldNotRevokeOtherUsersTokenOnLogout() {
        LogoutRequest request = new LogoutRequest("user-b-refresh-token");

        // Token belongs to User B
        AuthToken tokenB = new AuthToken("user-b", TokenType.REFRESH, "hash-b", Instant.now().plusSeconds(3600), Instant.now());

        when(tokenService.hashToken("user-b-refresh-token")).thenReturn("hash-b");
        when(authTokenRepository.findByTokenHash("hash-b")).thenReturn(Optional.of(tokenB));

        // User A attempts to logout with User B's token
        authService.logout("user-a", request);

        // Assert token B was NOT revoked and NOT saved
        assertFalse(tokenB.isRevoked());
        assertNull(tokenB.getRevokedAt());
        verify(authTokenRepository, never()).save(tokenB);
    }

    @Test
    @DisplayName("Issue 5: Owner logout revokes their own refresh token successfully")
    void shouldRevokeOwnTokenOnLogout() {
        LogoutRequest request = new LogoutRequest("owner-refresh-token");

        AuthToken ownerToken = new AuthToken("owner-1", TokenType.REFRESH, "hash-owner", Instant.now().plusSeconds(3600), Instant.now());

        when(tokenService.hashToken("owner-refresh-token")).thenReturn("hash-owner");
        when(authTokenRepository.findByTokenHash("hash-owner")).thenReturn(Optional.of(ownerToken));

        authService.logout("owner-1", request);

        assertTrue(ownerToken.isRevoked());
        assertNotNull(ownerToken.getRevokedAt());
        verify(authTokenRepository).save(ownerToken);
    }
}
