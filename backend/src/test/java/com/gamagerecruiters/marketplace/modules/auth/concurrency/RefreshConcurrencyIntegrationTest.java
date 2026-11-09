package com.gamagerecruiters.marketplace.modules.auth.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthService;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class RefreshConcurrencyIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        if (mongoDBContainer.isCreated() && mongoDBContainer.isRunning()) {
            registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        }
    }

    @Autowired(required = false)
    private AuthService authService;

    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private AuthTokenRepository authTokenRepository;

    @Autowired(required = false)
    private TokenService tokenService;

    @Autowired(required = false)
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        if (authTokenRepository != null) {
            authTokenRepository.deleteAll();
        }
        if (userRepository != null) {
            userRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("Issue D: 10 concurrent refreshes on real MongoDB -> exactly 1 succeeds, 9 receive 409 Conflict within grace window")
    void testParallelRefreshConcurrencyAgainstMongo() throws InterruptedException {
        // Create an active user
        User user = new User();
        user.setName("Concurrent Tester");
        user.setEmail("concurrent@marketplace.local");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        // Create a valid refresh token
        String rawRefreshToken = "concurrency-test-refresh-token-123";
        String tokenHash = tokenService.hashToken(rawRefreshToken);
        AuthToken token = new AuthToken(
                savedUser.getId(),
                TokenType.REFRESH,
                tokenHash,
                Instant.now().plusSeconds(604800),
                Instant.now()
        );
        authTokenRepository.save(token);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AuthResponse res = authService.refresh(new RefreshTokenRequest(rawRefreshToken));
                    if (res != null && res.accessToken() != null) {
                        successCount.incrementAndGet();
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
        startLatch.countDown(); // Release all 10 threads at once
        doneLatch.await();
        executor.shutdown();

        assertEquals(0, errors.size(), "Unexpected errors during concurrent refresh: " + errors);
        assertEquals(1, successCount.get(), "Exactly 1 concurrent refresh request must succeed");
        assertEquals(9, conflictCount.get(), "9 concurrent refresh requests within grace window must return 409 Conflict");

        // Verify that the newly issued refresh token is NOT revoked
        List<AuthToken> userTokens = authTokenRepository.findAll();
        boolean hasActiveToken = userTokens.stream()
                .anyMatch(t -> t.getRevokedAt() == null && t.getUsedAt() == null);
        assertTrue(hasActiveToken, "User must still possess an active, non-revoked session token");
    }
}
