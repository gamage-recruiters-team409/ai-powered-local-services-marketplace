package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailResult;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailSender;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailType;

@ExtendWith(MockitoExtension.class)
class EmailTokenServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthTokenRepository authTokenRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailSender emailSender;

    private EmailTokenServiceImpl emailTokenService;

    @BeforeEach
    void setUp() {
        emailTokenService = new EmailTokenServiceImpl(
                userRepository,
                authTokenRepository,
                tokenService,
                passwordEncoder,
                emailSender,
                1440,
                30,
                60,
                "http://localhost:5173"
        );
    }

    @Test
    @DisplayName("sendVerificationEmail creates token and delegates to emailSender with valid template data")
    void sendVerificationEmail_successful() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");
        user.setName("Jane Doe");

        when(tokenService.generateOpaqueToken()).thenReturn("raw-token-123");
        when(tokenService.hashToken("raw-token-123")).thenReturn("hash-123");
        when(emailSender.sendEmail(eq(EmailType.ACCOUNT_VERIFICATION), eq("user@example.com"), anyMap()))
                .thenReturn(EmailResult.success("mock-id"));

        emailTokenService.sendVerificationEmail(user);

        verify(authTokenRepository).revokeAllUnusedTokensByUserIdAndType("u123", TokenType.EMAIL_VERIFICATION);

        ArgumentCaptor<AuthToken> tokenCaptor = ArgumentCaptor.forClass(AuthToken.class);
        verify(authTokenRepository).save(tokenCaptor.capture());
        AuthToken saved = tokenCaptor.getValue();
        assertEquals("u123", saved.getUserId());
        assertEquals(TokenType.EMAIL_VERIFICATION, saved.getType());
        assertEquals("hash-123", saved.getTokenHash());
        assertNotNull(saved.getExpiresAt());

        verify(emailSender).sendEmail(
                eq(EmailType.ACCOUNT_VERIFICATION),
                eq("user@example.com"),
                argThat(data -> "Jane Doe".equals(data.get("name"))
                        && "http://localhost:5173/verify-email?token=raw-token-123".equals(data.get("verificationLink"))
                        && Integer.valueOf(1440).equals(data.get("expiresInMinutes")))
        );
    }

    @Test
    @DisplayName("sendVerificationEmail with null user or null id is a silent no-op")
    void sendVerificationEmail_nullUser_noop() {
        emailTokenService.sendVerificationEmail(null);
        emailTokenService.sendVerificationEmail(new User());

        verifyNoInteractions(emailSender);
        verifyNoInteractions(authTokenRepository);
    }

    @Test
    @DisplayName("sendVerificationEmail catches emailSender exception and does not propagate")
    void sendVerificationEmail_emailSenderThrows_doesNotPropagate() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");

        when(tokenService.generateOpaqueToken()).thenReturn("raw-token-123");
        when(tokenService.hashToken("raw-token-123")).thenReturn("hash-123");
        when(emailSender.sendEmail(any(), any(), any())).thenThrow(new RuntimeException("Mail error"));

        assertDoesNotThrow(() -> emailTokenService.sendVerificationEmail(user));
    }

    @Test
    @DisplayName("verifyEmail updates PENDING_VERIFICATION user to ACTIVE and emailVerified=true")
    void verifyEmail_pendingUser_becomesActiveAndVerified() {
        String rawToken = "raw-token-abc";
        String tokenHash = "hash-abc";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);
        when(userRepository.findById("u123")).thenReturn(Optional.of(user));

        emailTokenService.verifyEmail(rawToken);

        assertTrue(user.isEmailVerified());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        verify(userRepository).save(user);
        verify(authTokenRepository).consumeValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class));
    }

    @Test
    @DisplayName("verifyEmail on ACTIVE user keeps ACTIVE status and sets emailVerified=true")
    void verifyEmail_activeUser_updatesVerifiedOnly() {
        String rawToken = "raw-token-abc";
        String tokenHash = "hash-abc";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
        when(userRepository.findById("u123")).thenReturn(Optional.of(user));

        emailTokenService.verifyEmail(rawToken);

        assertTrue(user.isEmailVerified());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        verify(userRepository).save(user);
        verify(authTokenRepository).consumeValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class));
    }

    @Test
    @DisplayName("verifyEmail on SUSPENDED user keeps SUSPENDED status and sets emailVerified=true")
    void verifyEmail_suspendedUser_statusUnchanged() {
        String rawToken = "raw-token-abc";
        String tokenHash = "hash-abc";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.SUSPENDED);
        user.setEmailVerified(false);
        when(userRepository.findById("u123")).thenReturn(Optional.of(user));

        emailTokenService.verifyEmail(rawToken);

        assertTrue(user.isEmailVerified());
        assertEquals(UserStatus.SUSPENDED, user.getStatus());
        verify(userRepository).save(user);
        verify(authTokenRepository).consumeValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class));
    }

    @Test
    @DisplayName("verifyEmail throws 400 INVALID_OR_EXPIRED_TOKEN when token is unknown, expired, or already used")
    void verifyEmail_invalidToken_throws400() {
        when(tokenService.hashToken("invalid-token")).thenReturn("invalid-hash");
        when(authTokenRepository.findValidToken(eq("invalid-hash"), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> emailTokenService.verifyEmail("invalid-token"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_OR_EXPIRED_TOKEN", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(authTokenRepository, never()).consumeValidToken(any(), any(), any());
    }

    @Test
    @DisplayName("verifyEmail does not consume token and propagates exception when user save fails")
    void verifyEmail_userSaveFails_tokenUntouched() {
        String rawToken = "raw-token-abc";
        String tokenHash = "hash-abc";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findById("u123")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenThrow(new RuntimeException("Database error during save"));

        assertThrows(RuntimeException.class, () -> emailTokenService.verifyEmail(rawToken));

        verify(authTokenRepository, never()).consumeValidToken(any(), any(), any());
    }

    @Test
    @DisplayName("verifyEmail succeeds even if parallel request consumed token after user save")
    void verifyEmail_parallelConsume_treatedAsSuccess() {
        String rawToken = "raw-token-abc";
        String tokenHash = "hash-abc";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findById("u123")).thenReturn(Optional.of(user));
        when(authTokenRepository.consumeValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> emailTokenService.verifyEmail(rawToken));

        verify(userRepository).save(user);
        assertTrue(user.isEmailVerified());
    }

    @Test
    @DisplayName("verifyEmail succeeds even if consume throws after user save")
    void verifyEmail_consumeThrows_treatedAsSuccess() {
        String rawToken = "raw-token-abc";
        String tokenHash = "hash-abc";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findById("u123")).thenReturn(Optional.of(user));
        when(authTokenRepository.consumeValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class)))
                .thenThrow(new RuntimeException("Transient DB issue on consume"));

        assertDoesNotThrow(() -> emailTokenService.verifyEmail(rawToken));

        verify(userRepository).save(user);
        assertTrue(user.isEmailVerified());
    }

    @Test
    @DisplayName("verifyEmail throws 400 INVALID_OR_EXPIRED_TOKEN when token is null or blank")
    void verifyEmail_blankToken_throws400() {
        ApiException ex1 = assertThrows(ApiException.class, () -> emailTokenService.verifyEmail(null));
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatus());
        assertEquals("INVALID_OR_EXPIRED_TOKEN", ex1.getErrorCode());

        ApiException ex2 = assertThrows(ApiException.class, () -> emailTokenService.verifyEmail("   "));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatus());
        assertEquals("INVALID_OR_EXPIRED_TOKEN", ex2.getErrorCode());
    }

    @Test
    @DisplayName("resendVerification sends email when user is PENDING_VERIFICATION and unverified with no recent token")
    void resendVerification_eligibleUser_sendsEmail() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(authTokenRepository.findFirstByUserIdAndTypeOrderByCreatedAtDesc("u123", TokenType.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty());
        when(tokenService.generateOpaqueToken()).thenReturn("new-raw-token");
        when(tokenService.hashToken("new-raw-token")).thenReturn("new-hash");

        emailTokenService.resendVerification(" USER@Example.COM ");

        verify(emailSender).sendEmail(eq(EmailType.ACCOUNT_VERIFICATION), eq("user@example.com"), anyMap());
    }

    @Test
    @DisplayName("resendVerification does nothing when last token was created within 60s cooldown")
    void resendVerification_cooldownActive_silentNoop() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);

        AuthToken recentToken = new AuthToken("u123", TokenType.EMAIL_VERIFICATION, "hash", Instant.now().plus(1, ChronoUnit.HOURS), Instant.now().minus(20, ChronoUnit.SECONDS));

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(authTokenRepository.findFirstByUserIdAndTypeOrderByCreatedAtDesc("u123", TokenType.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(recentToken));

        emailTokenService.resendVerification("user@example.com");

        verifyNoInteractions(emailSender);
        verify(authTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("resendVerification does nothing when user is already verified or active")
    void resendVerification_alreadyVerified_silentNoop() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        emailTokenService.resendVerification("user@example.com");

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("resendVerification does nothing when user does not exist")
    void resendVerification_unknownEmail_silentNoop() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        emailTokenService.resendVerification("ghost@example.com");

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("forgotPassword sends email for ACTIVE user")
    void forgotPassword_activeUser_sendsEmail() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");
        user.setName("John Smith");
        user.setStatus(UserStatus.ACTIVE);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenService.generateOpaqueToken()).thenReturn("reset-raw");
        when(tokenService.hashToken("reset-raw")).thenReturn("reset-hash");

        emailTokenService.forgotPassword(" USER@Example.COM ");

        verify(authTokenRepository).revokeAllUnusedTokensByUserIdAndType("u123", TokenType.PASSWORD_RESET);
        verify(authTokenRepository).save(any(AuthToken.class));
        verify(emailSender).sendEmail(
                eq(EmailType.PASSWORD_RESET),
                eq("user@example.com"),
                argThat(data -> "John Smith".equals(data.get("name"))
                        && "http://localhost:5173/reset-password?token=reset-raw".equals(data.get("resetLink"))
                        && Integer.valueOf(30).equals(data.get("expiresInMinutes")))
        );
    }

    @Test
    @DisplayName("forgotPassword sends email for LOCKED user")
    void forgotPassword_lockedUser_sendsEmail() {
        User user = new User();
        user.setId("u123");
        user.setEmail("user@example.com");
        user.setStatus(UserStatus.LOCKED);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenService.generateOpaqueToken()).thenReturn("reset-raw");
        when(tokenService.hashToken("reset-raw")).thenReturn("reset-hash");

        emailTokenService.forgotPassword("user@example.com");

        verify(emailSender).sendEmail(eq(EmailType.PASSWORD_RESET), eq("user@example.com"), anyMap());
    }

    @Test
    @DisplayName("forgotPassword does nothing when user is SUSPENDED, DEACTIVATED, or PENDING_VERIFICATION")
    void forgotPassword_ineligibleStatus_silentNoop() {
        for (UserStatus ineligible : new UserStatus[]{UserStatus.SUSPENDED, UserStatus.DEACTIVATED, UserStatus.PENDING_VERIFICATION}) {
            User user = new User();
            user.setId("u123");
            user.setEmail("user@example.com");
            user.setStatus(ineligible);

            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

            emailTokenService.forgotPassword("user@example.com");

            verifyNoInteractions(emailSender);
        }
    }

    @Test
    @DisplayName("forgotPassword does nothing when user does not exist")
    void forgotPassword_unknownEmail_silentNoop() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        emailTokenService.forgotPassword("unknown@example.com");

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("resetPassword successfully updates password, clears lock and counters, and revokes sessions")
    void resetPassword_success() {
        String rawToken = "valid-reset-token";
        String tokenHash = "valid-reset-hash";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.PASSWORD_RESET, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));
        when(authTokenRepository.consumeValidToken(eq(tokenHash), eq(TokenType.PASSWORD_RESET), any(Instant.class)))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.LOCKED);
        user.setPasswordHash("oldEncodedPassword");
        user.setFailedLoginCount(5);
        user.setLockStartedAt(Instant.now());
        user.setMustChangePassword(true);

        when(userRepository.findById("u123")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("NewStrongPass1!", "oldEncodedPassword")).thenReturn(false);
        when(passwordEncoder.encode("NewStrongPass1!")).thenReturn("newEncodedPassword");

        emailTokenService.resetPassword(rawToken, "NewStrongPass1!");

        assertEquals("newEncodedPassword", user.getPasswordHash());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        assertNull(user.getLockStartedAt());
        assertFalse(user.isMustChangePassword());

        verify(userRepository).save(user);
        verify(authTokenRepository).revokeAllActiveTokensByUserIdAndType("u123", TokenType.REFRESH);
        verify(authTokenRepository).revokeAllUnusedTokensByUserIdAndType("u123", TokenType.PASSWORD_RESET);
    }

    @Test
    @DisplayName("resetPassword throws 400 SAME_PASSWORD when new password matches current password without consuming token")
    void resetPassword_samePassword_throws400() {
        String rawToken = "valid-reset-token";
        String tokenHash = "valid-reset-hash";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken token = new AuthToken("u123", TokenType.PASSWORD_RESET, tokenHash, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        when(authTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        User user = new User();
        user.setId("u123");
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("currentEncodedHash");

        when(userRepository.findById("u123")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SamePassword1!", "currentEncodedHash")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> emailTokenService.resetPassword(rawToken, "SamePassword1!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("SAME_PASSWORD", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(authTokenRepository, never()).consumeValidToken(anyString(), any(), any());
    }

    @Test
    @DisplayName("resetPassword throws 400 WEAK_PASSWORD when password fails complexity policy")
    void resetPassword_weakPassword_throws400() {
        String[] weakPasswords = {
                "short1A",        // < 8 chars
                "alllowercase1",  // no uppercase
                "ALLUPPERCASE1",  // no lowercase
                "NoDigitsHere!"   // no digit
        };

        for (String weak : weakPasswords) {
            ApiException ex = assertThrows(ApiException.class, () -> emailTokenService.resetPassword("token", weak));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("WEAK_PASSWORD", ex.getErrorCode());
        }

        verifyNoInteractions(authTokenRepository);
    }

    @Test
    @DisplayName("resetPassword throws 400 INVALID_OR_EXPIRED_TOKEN on token lookup failure")
    void resetPassword_invalidToken_throws400() {
        when(tokenService.hashToken("bad-token")).thenReturn("bad-hash");
        when(authTokenRepository.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> emailTokenService.resetPassword("bad-token", "ValidPassword123"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_OR_EXPIRED_TOKEN", ex.getErrorCode());
    }

    @Test
    @DisplayName("resetPassword throws 400 INVALID_OR_EXPIRED_TOKEN when token is expired")
    void resetPassword_expiredToken_throws400() {
        String rawToken = "expired-token";
        String tokenHash = "expired-hash";

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);

        AuthToken expiredToken = new AuthToken("u123", TokenType.PASSWORD_RESET, tokenHash, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().minus(2, ChronoUnit.HOURS));
        when(authTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(expiredToken));

        ApiException ex = assertThrows(ApiException.class, () -> emailTokenService.resetPassword(rawToken, "ValidPassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_OR_EXPIRED_TOKEN", ex.getErrorCode());
    }
}
