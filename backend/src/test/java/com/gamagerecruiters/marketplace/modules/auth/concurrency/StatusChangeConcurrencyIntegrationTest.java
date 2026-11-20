package com.gamagerecruiters.marketplace.modules.auth.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class StatusChangeConcurrencyIntegrationTest {

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
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
    private UserInternalService userInternalService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @BeforeEach
    void cleanDatabase() {
        authTokenRepository.deleteAll();
        userRepository.deleteAll();
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

    @Test
    @DisplayName("Real MongoDB Testcontainers: 10 concurrent status changes - exactly 1 succeeds, 9 return 409 CONCURRENT_MODIFICATION")
    void testConcurrentStatusChangesAgainstRealMongo() throws InterruptedException {
        // 1. Create active SUPER_ADMIN actor
        User actor = new User();
        actor.setId("actor-super-1");
        actor.setName("Super Admin");
        actor.setEmail("super@marketplace.local");
        actor.setRole(Role.SUPER_ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setCreatedAt(Instant.now());
        userRepository.save(actor);

        // 2. Create active target CUSTOMER user
        User target = new User();
        target.setId("target-user-1");
        target.setName("Target Customer");
        target.setEmail("target@marketplace.local");
        target.setRole(Role.CUSTOMER);
        target.setStatus(UserStatus.ACTIVE);
        target.setCreatedAt(Instant.now());
        userRepository.save(target);

        // 3. Issue an active refresh token for target user
        AuthToken token = new AuthToken(
                "target-user-1",
                TokenType.REFRESH,
                "token-hash-real-test",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(token);

        // 4. Fire 10 parallel threads attempting changeStatus(ACTIVE -> SUSPENDED)
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Exception> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                authenticateAs("actor-super-1", "super@marketplace.local", Role.SUPER_ADMIN);
                readyLatch.countDown();
                try {
                    startLatch.await();
                    User updated = userInternalService.changeStatus(
                            "actor-super-1",
                            "target-user-1",
                            UserStatus.ACTIVE,
                            UserStatus.SUSPENDED
                    );
                    if (updated != null && updated.getStatus() == UserStatus.SUSPENDED) {
                        successCount.incrementAndGet();
                    }
                } catch (ResourceConflictException e) {
                    if ("CONCURRENT_MODIFICATION".equals(e.getErrorCode())) {
                        conflictCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Exception e) {
                    unexpectedErrors.add(e);
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // 5. Verify assertions
        assertEquals(0, unexpectedErrors.size(), "Unexpected errors during concurrent status changes: " + unexpectedErrors);
        assertEquals(1, successCount.get(), "Exactly 1 concurrent status change must succeed");
        assertEquals(9, conflictCount.get(), "9 concurrent status changes must fail with 409 CONCURRENT_MODIFICATION");

        // 6. Verify final state in real database
        User finalUser = userRepository.findById("target-user-1").orElse(null);
        assertNotNull(finalUser);
        assertEquals(UserStatus.SUSPENDED, finalUser.getStatus());

        // 7. Verify refresh token was revoked
        AuthToken storedToken = authTokenRepository.findByTokenHash("token-hash-real-test").orElse(null);
        assertNotNull(storedToken);
        assertNotNull(storedToken.getRevokedAt(), "Refresh token must be revoked when user is SUSPENDED");
    }
}
