package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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
                15     // lockDurationMinutes = 15
        );
    }

    @Test
    @DisplayName("Should successfully register a new customer with active status when verification is false")
    void shouldRegisterCustomerSuccessfully() {
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
    @DisplayName("Should register with PENDING_VERIFICATION when email verification is required")
    void shouldRegisterWithPendingVerificationWhenRequired() {
        AuthServiceImpl serviceWithVerification = new AuthServiceImpl(
                userRepository,
                authTokenRepository,
                passwordEncoder,
                tokenService,
                true, // requireEmailVerification = true
                5,
                15
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
        user.setEmailVerified(true);

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
    @DisplayName("Should increment failed login count and lock after 5 failed attempts")
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
    @DisplayName("Should auto-unlock account after 15 minutes duration")
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
    @DisplayName("Should reject login with 403 when email is not verified")
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
    @DisplayName("Should reject refresh when token has already been used (reuse detection)")
    void shouldRejectReusedRefreshToken() {
        RefreshTokenRequest request = new RefreshTokenRequest("already-used-token");

        AuthToken token = new AuthToken("u-1", TokenType.REFRESH, "hash-used", Instant.now().plusSeconds(3600), Instant.now());
        token.setUsedAt(Instant.now()); // Marked used

        when(tokenService.hashToken("already-used-token")).thenReturn("hash-used");
        when(authTokenRepository.findByTokenHash("hash-used")).thenReturn(Optional.of(token));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(request));
        assertEquals("TOKEN_REUSED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Should rotate refresh token upon successful refresh")
    void shouldRotateRefreshTokenSuccessfully() {
        RefreshTokenRequest request = new RefreshTokenRequest("valid-refresh-token");

        AuthToken oldToken = new AuthToken("u-1", TokenType.REFRESH, "hash-old", Instant.now().plusSeconds(3600), Instant.now());
        User user = new User();
        user.setId("u-1");
        user.setName("John");
        user.setEmail("user@marketplace.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);

        when(tokenService.hashToken("valid-refresh-token")).thenReturn("hash-old");
        when(authTokenRepository.findByTokenHash("hash-old")).thenReturn(Optional.of(oldToken));
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));
        when(tokenService.generateAccessToken("u-1", "user@marketplace.local", Role.CUSTOMER)).thenReturn("new-jwt");
        when(tokenService.generateRefreshToken()).thenReturn("new-opaque-refresh");
        when(tokenService.hashToken("new-opaque-refresh")).thenReturn("hash-new");
        when(tokenService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(tokenService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AuthResponse response = authService.refresh(request);

        assertNotNull(response);
        assertEquals("new-jwt", response.accessToken());
        assertEquals("new-opaque-refresh", response.refreshToken());
        assertTrue(oldToken.isUsed()); // Old token marked used
        verify(authTokenRepository, org.mockito.Mockito.times(2)).save(any(AuthToken.class)); // old marked used + new token saved
    }

    @Test
    @DisplayName("Should revoke token on logout idempotently")
    void shouldRevokeTokenOnLogout() {
        LogoutRequest request = new LogoutRequest("valid-refresh-token");

        AuthToken token = new AuthToken("u-1", TokenType.REFRESH, "hash-logout", Instant.now().plusSeconds(3600), Instant.now());

        when(tokenService.hashToken("valid-refresh-token")).thenReturn("hash-logout");
        when(authTokenRepository.findByTokenHash("hash-logout")).thenReturn(Optional.of(token));

        authService.logout("u-1", request);

        assertTrue(token.isRevoked());
        verify(authTokenRepository).save(token);

        // Logout second time does not throw
        authService.logout("u-1", request);
    }
}
