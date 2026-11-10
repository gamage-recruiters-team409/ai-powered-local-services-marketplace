package com.gamagerecruiters.marketplace.modules.auth.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthService;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
class RefreshConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RefreshConcurrencyIntegrationTest.class);

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            // Container may not be started yet when Spring resolves properties lazily;
            // start it here as a safety net (idempotent if already running).
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        // DockerRequiredCondition guarantees Docker is available and skip is not requested.
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
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Autowired
    private TokenService tokenService;

    @BeforeEach
    void cleanDatabase() {
        authTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Finding 2: Real MongoDB Testcontainers concurrency test - 1 success, 9 409s, winner token works, expired token revokes")
    void testParallelRefreshConcurrencyAgainstRealMongo() throws InterruptedException {
        // Step 1: Register and login via service level
        RegisterRequest registerRequest = new RegisterRequest(
                "Concurrency Real User",
                "concurrent-real@marketplace.local",
                "+12345678901",
                "Password123!",
                Role.CUSTOMER,
                true,
                "v1.0"
        );
        authService.register(registerRequest);

        LoginRequest loginRequest = new LoginRequest("concurrent-real@marketplace.local", "Password123!");
        AuthResponse loginResponse = authService.login(loginRequest);
        String initialRefreshToken = loginResponse.refreshToken();
        assertNotNull(initialRefreshToken, "Login must yield an initial refresh token");

        // Step 2: Fire 10 simultaneous refresh calls with identical refresh token
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicReference<String> winningRefreshToken = new AtomicReference<>();
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AuthResponse res = authService.refresh(new RefreshTokenRequest(initialRefreshToken));
                    if (res != null && res.refreshToken() != null) {
                        successCount.incrementAndGet();
                        winningRefreshToken.set(res.refreshToken());
                    }
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT && "REFRESH_IN_PROGRESS".equals(e.getErrorCode())) {
                        conflictCount.incrementAndGet();
                    } else {
                        errors.add(e);
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Release all 10 threads concurrently
        doneLatch.await();
        executor.shutdown();

        // Step 3: Assert exactly 1 success and 9 conflicts
        assertEquals(0, errors.size(), "Unexpected exceptions during concurrent refresh: " + errors);
        assertEquals(1, successCount.get(), "Exactly 1 concurrent refresh request must succeed");
        assertEquals(9, conflictCount.get(), "9 concurrent refresh requests within grace window must return 409 Conflict");
        assertNotNull(winningRefreshToken.get(), "Winning request must provide a new refresh token");

        // Step 4: Verify the winner's new refresh token works
        AuthResponse nextRefresh = authService.refresh(new RefreshTokenRequest(winningRefreshToken.get()));
        assertNotNull(nextRefresh.accessToken(), "Winner token refresh must return access token");
        assertNotNull(nextRefresh.refreshToken(), "Winner token refresh must return new refresh token");

        // Step 5: Simulate that initialRefreshToken was consumed outside the grace window (> 10s ago)
        String oldTokenHash = tokenService.hashToken(initialRefreshToken);
        AuthToken oldToken = authTokenRepository.findByTokenHash(oldTokenHash)
                .orElseThrow(() -> new IllegalStateException("Consumed initial token not found in database"));
        oldToken.setUsedAt(Instant.now().minusSeconds(30));
        authTokenRepository.save(oldToken);

        // Step 6: Old token presented outside grace window returns 401 and revokes all user active tokens
        ApiException ex = assertThrows(
                ApiException.class,
                () -> authService.refresh(new RefreshTokenRequest(initialRefreshToken))
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("TOKEN_REUSED", ex.getErrorCode());

        // Step 7: Verify all active tokens for this user are now revoked
        String userId = oldToken.getUserId();
        List<AuthToken> userTokens = authTokenRepository.findAll();
        boolean hasActiveToken = userTokens.stream()
                .filter(t -> userId.equals(t.getUserId()))
                .anyMatch(t -> t.getRevokedAt() == null && t.getUsedAt() == null);
        assertFalse(hasActiveToken, "All active sessions for user must be revoked upon token reuse after grace window");
    }
}
