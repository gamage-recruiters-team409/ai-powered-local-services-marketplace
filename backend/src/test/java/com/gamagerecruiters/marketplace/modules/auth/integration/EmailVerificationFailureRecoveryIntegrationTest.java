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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;

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
public class EmailVerificationFailureRecoveryIntegrationTest {

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
    @DisplayName("Verification: userRepository.save fails once -> request fails, user still PENDING, token unused, retry succeeds")
    void testVerifyEmail_userSaveFailsOnce_retrySucceeds() {
        User user = new User();
        user.setEmail("verify-fail@marketplace.local");
        user.setName("Verify Fail");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);
        user.setPasswordHash("encodedPass123");
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(24));
        AuthToken token = new AuthToken(savedUser.getId(), TokenType.EMAIL_VERIFICATION, tokenHash, expiresAt, now);
        authTokenRepository.save(token);

        // Delegating wrapper that fails on save
        UserRepository failingUserRepository = Mockito.mock(UserRepository.class, AdditionalAnswers.delegatesTo(userRepository));
        Mockito.doThrow(new RuntimeException("Simulated DB failure on user activation save"))
                .when(failingUserRepository)
                .save(any(User.class));

        EmailTokenServiceImpl serviceWithFailure = createService(failingUserRepository, authTokenRepository);

        // Phase 1: Call with failing repository wrapper -> must throw
        assertThrows(RuntimeException.class, () -> serviceWithFailure.verifyEmail(rawToken));

        // Verify user is STILL pending and unverified
        User checkUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertEquals(UserStatus.PENDING_VERIFICATION, checkUser.getStatus());
        assertFalse(checkUser.isEmailVerified());

        // Verify token is STILL unused
        AuthToken checkToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNull(checkToken.getUsedAt(), "Token must not be burned when user save fails");
        assertTrue(checkToken.isValid());

        // Phase 2 (retry): Fresh service instance with plain real repository -> must succeed
        EmailTokenServiceImpl realService = createService(userRepository, authTokenRepository);
        assertDoesNotThrow(() -> realService.verifyEmail(rawToken));

        // Verify user is now ACTIVE and emailVerified=true
        User activeUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, activeUser.getStatus());
        assertTrue(activeUser.isEmailVerified());

        // Verify token is now marked as used
        AuthToken usedToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNotNull(usedToken.getUsedAt(), "Token must be consumed on successful verification");
        assertFalse(usedToken.isValid());
    }

    @Test
    @DisplayName("Verification: token consume returns empty after successful activation -> still returns success")
    void testVerifyEmail_parallelConsume_treatedAsSuccess() {
        User user = new User();
        user.setEmail("parallel-consume@marketplace.local");
        user.setName("Parallel User");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setEmailVerified(false);
        user.setPasswordHash("encodedPass123");
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(24));
        AuthToken token = new AuthToken(savedUser.getId(), TokenType.EMAIL_VERIFICATION, tokenHash, expiresAt, now);
        authTokenRepository.save(token);

        // Delegating wrapper whose consumeValidToken returns empty (parallel request already consumed)
        AuthTokenRepository delegatingTokenRepo = Mockito.mock(AuthTokenRepository.class, AdditionalAnswers.delegatesTo(authTokenRepository));
        Mockito.doReturn(Optional.empty())
                .when(delegatingTokenRepo)
                .consumeValidToken(eq(tokenHash), eq(TokenType.EMAIL_VERIFICATION), any(Instant.class));

        EmailTokenServiceImpl service = createService(userRepository, delegatingTokenRepo);

        // Verification must still succeed because the account activation succeeded
        assertDoesNotThrow(() -> service.verifyEmail(rawToken));

        User activeUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, activeUser.getStatus());
        assertTrue(activeUser.isEmailVerified());
    }

    @Test
    @DisplayName("Repository: findValidToken ignores expired, used, or revoked tokens")
    void testFindValidToken_ignoresInvalidTokens() {
        String userId = "u-repo-test";
        Instant now = Instant.now();

        // 1. Valid token
        AuthToken validToken = new AuthToken(userId, TokenType.EMAIL_VERIFICATION, "hash-valid", now.plusSeconds(3600), now);
        authTokenRepository.save(validToken);
        Optional<AuthToken> foundValid = authTokenRepository.findValidToken("hash-valid", TokenType.EMAIL_VERIFICATION, now);
        assertTrue(foundValid.isPresent());

        // 2. Expired token
        AuthToken expiredToken = new AuthToken(userId, TokenType.EMAIL_VERIFICATION, "hash-expired", now.minusSeconds(60), now.minusSeconds(3600));
        authTokenRepository.save(expiredToken);
        Optional<AuthToken> foundExpired = authTokenRepository.findValidToken("hash-expired", TokenType.EMAIL_VERIFICATION, now);
        assertTrue(foundExpired.isEmpty());

        // 3. Used token
        AuthToken usedToken = new AuthToken(userId, TokenType.EMAIL_VERIFICATION, "hash-used", now.plusSeconds(3600), now);
        usedToken.setUsedAt(now);
        authTokenRepository.save(usedToken);
        Optional<AuthToken> foundUsed = authTokenRepository.findValidToken("hash-used", TokenType.EMAIL_VERIFICATION, now);
        assertTrue(foundUsed.isEmpty());

        // 4. Revoked token
        AuthToken revokedToken = new AuthToken(userId, TokenType.EMAIL_VERIFICATION, "hash-revoked", now.plusSeconds(3600), now);
        revokedToken.setRevokedAt(now);
        authTokenRepository.save(revokedToken);
        Optional<AuthToken> foundRevoked = authTokenRepository.findValidToken("hash-revoked", TokenType.EMAIL_VERIFICATION, now);
        assertTrue(foundRevoked.isEmpty());

        // 5. Wrong type
        Optional<AuthToken> foundWrongType = authTokenRepository.findValidToken("hash-valid", TokenType.PASSWORD_RESET, now);
        assertTrue(foundWrongType.isEmpty());
    }
}
