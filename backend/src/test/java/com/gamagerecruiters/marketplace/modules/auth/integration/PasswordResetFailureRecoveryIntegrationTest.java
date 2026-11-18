package com.gamagerecruiters.marketplace.modules.auth.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.service.EmailTokenServiceImpl;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailResult;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailSender;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
public class PasswordResetFailureRecoveryIntegrationTest {

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
        registry.add("app.auth.require-email-verification", () -> "true");
        registry.add("app.rate-limit.enabled", () -> "false");
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer != null && mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailSender emailSender;

    @Value("${app.auth.email-verification-expiry-minutes:1440}")
    private int emailVerificationExpiryMinutes;

    @Value("${app.auth.password-reset-expiry-minutes:30}")
    private int passwordResetExpiryMinutes;

    @Value("${app.auth.resend-verification-wait-seconds:60}")
    private int resendVerificationWaitSeconds;

    @Value("${app.frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    private EmailTokenServiceImpl createService(UserRepository userRepo, AuthTokenRepository tokenRepo) {
        return new EmailTokenServiceImpl(
                userRepo,
                tokenRepo,
                tokenService,
                passwordEncoder,
                emailSender,
                emailVerificationExpiryMinutes,
                passwordResetExpiryMinutes,
                resendVerificationWaitSeconds,
                frontendBaseUrl
        );
    }

    @BeforeEach
    void cleanDatabase() {
        authTokenRepository.deleteAll();
        userRepository.deleteAll();
        when(emailSender.sendEmail(any(EmailType.class), any(), anyMap()))
                .thenReturn(EmailResult.success("mock-email-id"));
    }

    @Test
    @DisplayName("Reset: userRepository.save fails once -> request fails, password unchanged, token usable again, retry succeeds")
    void testResetPassword_userSaveFailsOnce_compensationAllowsRetry() {
        User user = new User();
        user.setEmail("reset-save-fail@marketplace.local");
        user.setName("Reset Save Fail");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        String initialPassword = "OldPassword123!";
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setMustChangePassword(true);
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        // Active refresh token for this user
        AuthToken refreshToken = new AuthToken(savedUser.getId(), TokenType.REFRESH, "refresh-hash-1", Instant.now().plusSeconds(3600), Instant.now());
        authTokenRepository.save(refreshToken);

        // Reset token
        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(30));
        AuthToken resetToken = new AuthToken(savedUser.getId(), TokenType.PASSWORD_RESET, tokenHash, expiresAt, now);
        authTokenRepository.save(resetToken);

        // Delegating wrapper that fails on save
        UserRepository failingUserRepository = Mockito.mock(UserRepository.class, AdditionalAnswers.delegatesTo(userRepository));
        Mockito.doThrow(new RuntimeException("Simulated DB failure on password save"))
                .when(failingUserRepository)
                .save(any(User.class));

        EmailTokenServiceImpl serviceWithSaveFailure = createService(failingUserRepository, authTokenRepository);
        String newPassword = "NewPassword123!";

        // Phase 1: Call with failing repository wrapper -> must throw
        assertThrows(RuntimeException.class, () -> serviceWithSaveFailure.resetPassword(rawToken, newPassword));

        // Check password hash is UNCHANGED
        User checkUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(initialPassword, checkUser.getPasswordHash()));
        assertTrue(checkUser.isMustChangePassword());

        // Check reset token is USABLE again (usedAt null)
        AuthToken checkToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNull(checkToken.getUsedAt(), "Reset token usedAt must be reset to null via compensation");
        assertTrue(checkToken.isValid(), "Token must be valid for retry");

        // Phase 2 (retry): Fresh service instance with plain real repository -> must succeed
        EmailTokenServiceImpl realService = createService(userRepository, authTokenRepository);
        assertDoesNotThrow(() -> realService.resetPassword(rawToken, newPassword));

        // Verify password changed, mustChangePassword cleared
        User updatedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(newPassword, updatedUser.getPasswordHash()));
        assertFalse(updatedUser.isMustChangePassword());

        // Verify reset token consumed
        AuthToken finalToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNotNull(finalToken.getUsedAt());

        // Verify all refresh tokens revoked
        AuthToken finalRefreshToken = authTokenRepository.findByTokenHash("refresh-hash-1").orElseThrow();
        assertNotNull(finalRefreshToken.getRevokedAt());
    }

    @Test
    @DisplayName("Reset: refresh-token revocation fails once -> request fails, password unchanged, token usable again, retry succeeds")
    void testResetPassword_revokeRefreshFailsOnce_compensationAllowsRetry() {
        User user = new User();
        user.setEmail("reset-revoke-fail@marketplace.local");
        user.setName("Revoke Fail User");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        String initialPassword = "OldPassword123!";
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setMustChangePassword(true);
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        // Active refresh token
        AuthToken refreshToken = new AuthToken(savedUser.getId(), TokenType.REFRESH, "refresh-hash-2", Instant.now().plusSeconds(3600), Instant.now());
        authTokenRepository.save(refreshToken);

        // Reset token
        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(30));
        AuthToken resetToken = new AuthToken(savedUser.getId(), TokenType.PASSWORD_RESET, tokenHash, expiresAt, now);
        authTokenRepository.save(resetToken);

        // Delegating wrapper that fails on refresh token revocation
        AuthTokenRepository failingTokenRepo = Mockito.mock(AuthTokenRepository.class, AdditionalAnswers.delegatesTo(authTokenRepository));
        Mockito.doThrow(new RuntimeException("Simulated DB failure during refresh token revocation"))
                .when(failingTokenRepo)
                .revokeAllActiveTokensByUserIdAndType(eq(savedUser.getId()), eq(TokenType.REFRESH));

        EmailTokenServiceImpl serviceWithRevocationFailure = createService(userRepository, failingTokenRepo);
        String newPassword = "NewPassword123!";

        // Phase 1: Call with failing repository wrapper -> must throw
        assertThrows(RuntimeException.class, () -> serviceWithRevocationFailure.resetPassword(rawToken, newPassword));

        // Check password hash unchanged
        User checkUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(initialPassword, checkUser.getPasswordHash()));

        // Check reset token released via compensation
        AuthToken checkToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNull(checkToken.getUsedAt());
        assertTrue(checkToken.isValid());

        // Phase 2 (retry): Fresh service instance with plain real repository -> must succeed
        EmailTokenServiceImpl realService = createService(userRepository, authTokenRepository);
        assertDoesNotThrow(() -> realService.resetPassword(rawToken, newPassword));

        User updatedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(newPassword, updatedUser.getPasswordHash()));
        assertFalse(updatedUser.isMustChangePassword());
    }

    @Test
    @DisplayName("Reset: concurrent double submit of the same link -> exactly one success")
    void testResetPassword_concurrentDoubleSubmit_exactlyOneSuccess() throws Exception {
        User user = new User();
        user.setEmail("reset-concurrent@marketplace.local");
        user.setName("Concurrent Reset");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode("InitialPass123!"));
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        AuthToken resetToken = new AuthToken(savedUser.getId(), TokenType.PASSWORD_RESET, tokenHash, now.plus(Duration.ofMinutes(30)), now);
        authTokenRepository.save(resetToken);

        EmailTokenServiceImpl realService = createService(userRepository, authTokenRepository);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String newPassword = "NewPassword123" + i + "!";
            executor.submit(() -> {
                try {
                    startLatch.await();
                    realService.resetPassword(rawToken, newPassword);
                    successCount.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus() == HttpStatus.BAD_REQUEST) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    // Ignored
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent reset request must succeed");
        assertEquals(1, rejectedCount.get(), "The other concurrent request must be rejected with 400 Bad Request");
    }

    @Test
    @DisplayName("Repository: releaseConsumedToken releases used token but not revoked token")
    void testReleaseConsumedToken_behavior() {
        String userId = "u-comp-test";
        Instant now = Instant.now();

        // 1. Used, non-revoked token -> successfully released
        AuthToken usedToken = new AuthToken(userId, TokenType.PASSWORD_RESET, "hash-used-1", now.plusSeconds(1800), now);
        usedToken.setUsedAt(now);
        authTokenRepository.save(usedToken);

        Optional<AuthToken> released = authTokenRepository.releaseConsumedToken("hash-used-1", TokenType.PASSWORD_RESET);
        assertTrue(released.isPresent());
        assertNull(released.get().getUsedAt());

        // Verify in DB
        AuthToken check = authTokenRepository.findByTokenHash("hash-used-1").orElseThrow();
        assertNull(check.getUsedAt());

        // 2. Unused token (usedAt is null) -> release does nothing
        Optional<AuthToken> unusedRelease = authTokenRepository.releaseConsumedToken("hash-used-1", TokenType.PASSWORD_RESET);
        assertTrue(unusedRelease.isEmpty());

        // 3. Revoked token -> release does nothing
        AuthToken revokedToken = new AuthToken(userId, TokenType.PASSWORD_RESET, "hash-revoked-1", now.plusSeconds(1800), now);
        revokedToken.setUsedAt(now);
        revokedToken.setRevokedAt(now);
        authTokenRepository.save(revokedToken);

        Optional<AuthToken> revokedRelease = authTokenRepository.releaseConsumedToken("hash-revoked-1", TokenType.PASSWORD_RESET);
        assertTrue(revokedRelease.isEmpty());

        // 4. Mismatched type -> release does nothing
        AuthToken emailToken = new AuthToken(userId, TokenType.EMAIL_VERIFICATION, "hash-email-1", now.plusSeconds(1800), now);
        emailToken.setUsedAt(now);
        authTokenRepository.save(emailToken);

        Optional<AuthToken> wrongTypeRelease = authTokenRepository.releaseConsumedToken("hash-email-1", TokenType.PASSWORD_RESET);
        assertTrue(wrongTypeRelease.isEmpty());
    }
}
