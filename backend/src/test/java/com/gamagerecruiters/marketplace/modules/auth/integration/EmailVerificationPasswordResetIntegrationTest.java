package com.gamagerecruiters.marketplace.modules.auth.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.ForgotPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.ResetPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.VerifyEmailRequest;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailResult;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailSender;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
public class EmailVerificationPasswordResetIntegrationTest {

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @MockitoBean
    private EmailSender emailSender;

    @BeforeEach
    void cleanDatabase() {
        authTokenRepository.deleteAll();
        userRepository.deleteAll();
        when(emailSender.sendEmail(any(EmailType.class), any(), anyMap()))
                .thenReturn(EmailResult.success("mock-email-id"));
    }

    @Test
    @DisplayName("Full Flow: Register -> Email sent -> Verify -> Login -> Forgot Password -> Reset Password -> Relogin")
    void testFullRegistrationVerificationAndRecoveryFlow() throws Exception {
        // 1. Register with email verification required
        RegisterRequest registerRequest = new RegisterRequest(
                "Alice Wonder",
                "alice@marketplace.local",
                "+12345678901",
                "Password123!",
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        User unverifiedUser = userRepository.findByEmail("alice@marketplace.local").orElseThrow();
        assertEquals(UserStatus.PENDING_VERIFICATION, unverifiedUser.getStatus());
        assertFalse(unverifiedUser.isEmailVerified());

        // 2. Login fails before verification
        LoginRequest unverifiedLogin = new LoginRequest("alice@marketplace.local", "Password123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unverifiedLogin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));

        // 3. Capture verification email and extract token
        ArgumentCaptor<Map<String, Object>> verificationDataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailSender, atLeastOnce()).sendEmail(
                eq(EmailType.ACCOUNT_VERIFICATION),
                eq("alice@marketplace.local"),
                verificationDataCaptor.capture()
        );

        String verificationLink = (String) verificationDataCaptor.getValue().get("verificationLink");
        assertNotNull(verificationLink);
        String verifyToken = extractTokenFromLink(verificationLink);
        assertNotNull(verifyToken);

        // 4. Verify email
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(verifyToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email verified successfully"));

        User verifiedUser = userRepository.findByEmail("alice@marketplace.local").orElseThrow();
        assertEquals(UserStatus.ACTIVE, verifiedUser.getStatus());
        assertTrue(verifiedUser.isEmailVerified());

        // 5. Token reuse fails
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(verifyToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_OR_EXPIRED_TOKEN"));

        // 6. Login now succeeds
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unverifiedLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String initialRefreshToken = loginJson.get("refreshToken").asText();

        // 7. Forgot password
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("alice@marketplace.local"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an account exists for this email, a reset link has been sent"));

        ArgumentCaptor<Map<String, Object>> resetDataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailSender, atLeastOnce()).sendEmail(
                eq(EmailType.PASSWORD_RESET),
                eq("alice@marketplace.local"),
                resetDataCaptor.capture()
        );

        String resetLink = (String) resetDataCaptor.getValue().get("resetLink");
        assertNotNull(resetLink);
        String resetToken = extractTokenFromLink(resetLink);
        assertNotNull(resetToken);

        // 8. Reset password fails with same password
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(resetToken, "Password123!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SAME_PASSWORD"));

        // 9. Reset password succeeds with new password
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(resetToken, "NewSecurePassword456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successfully"));

        // 10. Old password fails
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice@marketplace.local", "Password123!"))))
                .andExpect(status().isUnauthorized());

        // 11. Old refresh token is revoked
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(initialRefreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REVOKED"));

        // 12. Login with new password succeeds
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("alice@marketplace.local", "NewSecurePassword456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 13. Reset token cannot be reused
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(resetToken, "AnotherPassword789!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    @DisplayName("Concurrent consumption on Mongo: exactly one thread succeeds in consuming token")
    void testConcurrentConsumeValidTokenOnMongo() throws InterruptedException {
        String tokenHash = "mongo-concurrent-hash-123";
        Instant now = Instant.now();
        Instant expiresAt = now.plus(1, ChronoUnit.HOURS);

        AuthToken authToken = new AuthToken("u999", TokenType.EMAIL_VERIFICATION, tokenHash, expiresAt, now);
        authTokenRepository.save(authToken);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<Optional<AuthToken>> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Optional<AuthToken> consumed = authTokenRepository.consumeValidToken(
                            tokenHash,
                            TokenType.EMAIL_VERIFICATION,
                            Instant.now()
                    );
                    results.add(consumed);
                    if (consumed.isPresent()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    // ignore
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one thread must successfully consume the token");
        assertEquals(threadCount, results.size());

        AuthToken updated = authTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertTrue(updated.isUsed());
        assertNotNull(updated.getUsedAt());
    }

    private static String extractTokenFromLink(String link) {
        int idx = link.indexOf("token=");
        if (idx == -1) {
            return null;
        }
        return link.substring(idx + 6);
    }
}
