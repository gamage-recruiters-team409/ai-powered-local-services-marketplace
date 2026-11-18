package com.gamagerecruiters.marketplace.modules.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.ResetPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private UserRepository userRepository;

    @MockitoSpyBean
    private AuthTokenRepository authTokenRepository;

    @MockitoBean
    private EmailSender emailSender;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        doCallRealMethod().when(userRepository).save(any(User.class));
        doCallRealMethod().when(authTokenRepository).revokeAllActiveTokensByUserIdAndType(anyString(), any(TokenType.class));
        authTokenRepository.deleteAll();
        userRepository.deleteAll();
        when(emailSender.sendEmail(any(EmailType.class), any(), anyMap()))
                .thenReturn(EmailResult.success("mock-email-id"));
    }

    @Test
    @DisplayName("Reset: userRepository.save fails once -> request fails, password unchanged, token usable again, retry succeeds")
    void testResetPassword_userSaveFailsOnce_compensationAllowsRetry() throws Exception {
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

        // Make userRepository.save fail on next call (inside resetPassword), then succeed on retry
        doThrow(new RuntimeException("Simulated DB failure on password save"))
                .doCallRealMethod()
                .when(userRepository)
                .save(any(User.class));

        String newPassword = "NewPassword123!";
        ResetPasswordRequest req = new ResetPasswordRequest(rawToken, newPassword);

        // First attempt: must fail
        MvcResult failResult = mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();

        assertThat(failResult.getResponse().getStatus()).isEqualTo(500);

        // Check password hash is UNCHANGED
        User checkUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(initialPassword, checkUser.getPasswordHash()));
        assertTrue(checkUser.isMustChangePassword());

        // Check reset token is USABLE again (usedAt null)
        AuthToken checkToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNull(checkToken.getUsedAt(), "Reset token usedAt must be reset to null via compensation");
        assertTrue(checkToken.isValid(), "Token must be valid for retry");

        // Retry with the EXACT SAME link/token: must succeed
        MvcResult retryResult = mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();

        assertThat(retryResult.getResponse().getStatus()).isEqualTo(200);

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
    void testResetPassword_revokeRefreshFailsOnce_compensationAllowsRetry() throws Exception {
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

        // Make revocation fail on first call, succeed on retry
        doThrow(new RuntimeException("Simulated DB failure during refresh token revocation"))
                .doCallRealMethod()
                .when(authTokenRepository)
                .revokeAllActiveTokensByUserIdAndType(savedUser.getId(), TokenType.REFRESH);

        String newPassword = "NewPassword123!";
        ResetPasswordRequest req = new ResetPasswordRequest(rawToken, newPassword);

        // First attempt: must fail
        MvcResult failResult = mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();

        assertThat(failResult.getResponse().getStatus()).isEqualTo(500);

        // Check password hash unchanged
        User checkUser = userRepository.findById(savedUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(initialPassword, checkUser.getPasswordHash()));

        // Check reset token released via compensation
        AuthToken checkToken = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertNull(checkToken.getUsedAt());
        assertTrue(checkToken.isValid());

        // Retry with same token: succeeds
        MvcResult retryResult = mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();

        assertThat(retryResult.getResponse().getStatus()).isEqualTo(200);

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

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final String newPassword = "NewPassword123" + i + "!";
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/auth/reset-password")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, newPassword))))
                            .andReturn();
                    int status = res.getResponse().getStatus();
                    statuses.add(status);
                    if (status == 200) {
                        successCount.incrementAndGet();
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
        assertEquals(2, statuses.size());
        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(400));
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
