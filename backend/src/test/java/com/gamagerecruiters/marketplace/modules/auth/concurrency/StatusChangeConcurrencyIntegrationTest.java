package com.gamagerecruiters.marketplace.modules.auth.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
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
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalServiceImpl;

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

    @MockitoSpyBean
    private AuthTokenRepository authTokenRepository;

    @MockitoSpyBean
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanDatabase() {
        reset(authTokenRepository);
        reset(mongoTemplate);
        authTokenRepository.deleteAll();
        userRepository.deleteAll();
        mongoTemplate.remove(new Query(), "auth_locks");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        reset(authTokenRepository);
        reset(mongoTemplate);
        SecurityContextHolder.clearContext();
    }

    private User createUserRecord(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setName("User " + id);
        user.setEmail(id + "@marketplace.local");
        user.setRole(role);
        user.setStatus(status);
        user.setCreatedAt(Instant.now());
        return user;
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

    @Test
    @DisplayName("F2 Real MongoDB Testcontainers: 2 concurrent SUPER_ADMINs suspending each other across 50 iterations never leaves 0 active SUPER_ADMINs")
    void testConcurrentMutualSuperAdminSuspensionMaintainsAtLeastOneActive() throws InterruptedException {
        int iterations = 50;

        for (int iter = 0; iter < iterations; iter++) {
            // Clean users for this iteration
            userRepository.deleteAll();

            // Create SUPER_ADMIN A
            User adminA = new User();
            adminA.setId("super-a-" + iter);
            adminA.setName("Super Admin A");
            adminA.setEmail("super-a-" + iter + "@marketplace.local");
            adminA.setRole(Role.SUPER_ADMIN);
            adminA.setStatus(UserStatus.ACTIVE);
            adminA.setCreatedAt(Instant.now());
            userRepository.save(adminA);

            // Create SUPER_ADMIN B
            User adminB = new User();
            adminB.setId("super-b-" + iter);
            adminB.setName("Super Admin B");
            adminB.setEmail("super-b-" + iter + "@marketplace.local");
            adminB.setRole(Role.SUPER_ADMIN);
            adminB.setStatus(UserStatus.ACTIVE);
            adminB.setCreatedAt(Instant.now());
            userRepository.save(adminB);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            final String idA = adminA.getId();
            final String idB = adminB.getId();
            final String emailA = adminA.getEmail();
            final String emailB = adminB.getEmail();

            // Thread 1: A suspends B
            executor.submit(() -> {
                authenticateAs(idA, emailA, Role.SUPER_ADMIN);
                readyLatch.countDown();
                try {
                    startLatch.await();
                    userInternalService.changeStatus(idA, idB, UserStatus.ACTIVE, UserStatus.SUSPENDED);
                } catch (Exception ignored) {
                    // May fail with LAST_ACTIVE_SUPER_ADMIN or CONCURRENT_MODIFICATION
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            // Thread 2: B suspends A
            executor.submit(() -> {
                authenticateAs(idB, emailB, Role.SUPER_ADMIN);
                readyLatch.countDown();
                try {
                    startLatch.await();
                    userInternalService.changeStatus(idB, idA, UserStatus.ACTIVE, UserStatus.SUSPENDED);
                } catch (Exception ignored) {
                    // May fail with LAST_ACTIVE_SUPER_ADMIN or CONCURRENT_MODIFICATION
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            readyLatch.await();
            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            long activeSuperAdmins = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            assertTrue(activeSuperAdmins >= 1,
                    "Iteration " + iter + ": Invariant violated! Expected >= 1 active SUPER_ADMIN but found: " + activeSuperAdmins);
        }
    }

    @Test
    @DisplayName("F2 Real MongoDB Testcontainers: 3 concurrent SUPER_ADMINs suspending each other across 30 iterations never leaves 0 active SUPER_ADMINs")
    void testConcurrentThreeSuperAdminSuspensionMaintainsAtLeastOneActive() throws InterruptedException {
        int iterations = 30;

        for (int iter = 0; iter < iterations; iter++) {
            userRepository.deleteAll();
            mongoTemplate.remove(new Query(), "auth_locks");

            User adminA = createUserRecord("super-3a-" + iter, Role.SUPER_ADMIN, UserStatus.ACTIVE);
            User adminB = createUserRecord("super-3b-" + iter, Role.SUPER_ADMIN, UserStatus.ACTIVE);
            User adminC = createUserRecord("super-3c-" + iter, Role.SUPER_ADMIN, UserStatus.ACTIVE);
            userRepository.saveAll(List.of(adminA, adminB, adminC));

            int threadCount = 6;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            String[][] pairs = {
                    {adminA.getId(), adminA.getEmail(), adminB.getId()},
                    {adminA.getId(), adminA.getEmail(), adminC.getId()},
                    {adminB.getId(), adminB.getEmail(), adminA.getId()},
                    {adminB.getId(), adminB.getEmail(), adminC.getId()},
                    {adminC.getId(), adminC.getEmail(), adminA.getId()},
                    {adminC.getId(), adminC.getEmail(), adminB.getId()}
            };

            for (String[] pair : pairs) {
                final String actorId = pair[0];
                final String actorEmail = pair[1];
                final String targetId = pair[2];

                executor.submit(() -> {
                    authenticateAs(actorId, actorEmail, Role.SUPER_ADMIN);
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        userInternalService.changeStatus(actorId, targetId, UserStatus.ACTIVE, UserStatus.SUSPENDED);
                    } catch (Exception ignored) {
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

            long activeSuperAdmins = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            assertTrue(activeSuperAdmins >= 1,
                    "Iteration " + iter + ": Invariant violated! Expected >= 1 active SUPER_ADMIN but found: " + activeSuperAdmins);
        }
    }

    @Test
    @DisplayName("Guard Real MongoDB: Expired super-admin status lock lease can be acquired by a new operation")
    void testExpiredSuperAdminGuardLeaseCanBeTakenOver() {
        // 1. Manually insert an expired lock document in auth_locks collection
        Document expiredLock = new Document("_id", "super-admin-status")
                .append("owner", "old-expired-owner-uuid")
                .append("lockedUntil", Instant.now().minusSeconds(30));
        mongoTemplate.save(expiredLock, "auth_locks");

        // 2. Create two active SUPER_ADMINs
        User adminA = createUserRecord("super-lease-a", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User adminB = createUserRecord("super-lease-b", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.saveAll(List.of(adminA, adminB));

        authenticateAs(adminA.getId(), adminA.getEmail(), Role.SUPER_ADMIN);

        // 3. A suspends B -> Guard acquisition should overwrite the expired lock
        User updatedB = userInternalService.changeStatus(adminA.getId(), adminB.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
        assertNotNull(updatedB);
        assertEquals(UserStatus.SUSPENDED, updatedB.getStatus());

        // 4. Verify in real database: B is suspended, and the lock was released in finally block
        User persistedB = userRepository.findById(adminB.getId()).orElse(null);
        assertNotNull(persistedB);
        assertEquals(UserStatus.SUSPENDED, persistedB.getStatus());

        Document lockDoc = mongoTemplate.findById("super-admin-status", Document.class, "auth_locks");
        assertNotNull(lockDoc);
        assertFalse(lockDoc.containsKey("owner"), "Owner must be unset upon lock release");
    }

    @Test
    @DisplayName("Guard Real MongoDB: Failure injected after guard acquisition leaves user ACTIVE, releases guard, and retry succeeds")
    void testFailureAfterGuardAcquisitionLeavesGuardReleasedAndRetrySucceeds() {
        // 1. Create two active SUPER_ADMINs
        User adminA = createUserRecord("super-fail-a", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        User adminB = createUserRecord("super-fail-b", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.saveAll(List.of(adminA, adminB));

        authenticateAs(adminA.getId(), adminA.getEmail(), Role.SUPER_ADMIN);

        try {
            // 2. Inject failure on mongoTemplate.updateFirst for User.class (after guard acquisition and token revocation)
            doThrow(new RuntimeException("Simulated DB connection drop during status update"))
                    .when(mongoTemplate)
                    .updateFirst(any(Query.class), any(Update.class), eq(User.class));

            ApiException ex = assertThrows(ApiException.class, () ->
                    userInternalService.changeStatus(adminA.getId(), adminB.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED));

            assertEquals("STATUS_CHANGE_FAILED", ex.getErrorCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertEquals("The status change could not be confirmed. Please reload the user and retry.", ex.getMessage());

            // 3. Invariant holds: both SUPER_ADMINs remain ACTIVE
            User userB = userRepository.findById(adminB.getId()).orElse(null);
            assertNotNull(userB);
            assertEquals(UserStatus.ACTIVE, userB.getStatus());

            long activeCount = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            assertEquals(2L, activeCount);

            // 4. Guard is released in finally block
            Document lockDoc = mongoTemplate.findById("super-admin-status", Document.class, "auth_locks");
            assertNotNull(lockDoc);
            assertFalse(lockDoc.containsKey("owner"), "Lock owner must be unset after exception in update");

            // 5. Reset mongoTemplate spy so next attempt works
            reset(mongoTemplate);

            // 6. Next attempt works normally
            User retryUpdatedB = userInternalService.changeStatus(adminA.getId(), adminB.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
            assertNotNull(retryUpdatedB);
            assertEquals(UserStatus.SUSPENDED, retryUpdatedB.getStatus());

            User finalB = userRepository.findById(adminB.getId()).orElse(null);
            assertNotNull(finalB);
            assertEquals(UserStatus.SUSPENDED, finalB.getStatus());
        } finally {
            reset(mongoTemplate);
        }
    }

    @Test
    @DisplayName("F3 Real MongoDB Testcontainers: Token revocation failure leaves status, failedLoginCount, and lockStartedAt intact; retry succeeds")
    void testTokenRevocationFailureLeavesStatusAndAuthStateIntactAndRetrySucceeds() {
        // --- Case 1: Target is ACTIVE with failedLoginCount = 3 ---
        User actor = createUserRecord("actor-super-f3-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.save(actor);

        User targetActive = createUserRecord("target-user-f3-1", Role.CUSTOMER, UserStatus.ACTIVE);
        targetActive.setFailedLoginCount(3);
        targetActive.setLockStartedAt(null);
        userRepository.save(targetActive);

        AuthToken token1 = new AuthToken(
                targetActive.getId(),
                TokenType.REFRESH,
                "token-hash-f3-1",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(token1);

        authenticateAs(actor.getId(), actor.getEmail(), Role.SUPER_ADMIN);

        try {
            // Revocation throws once
            doThrow(new RuntimeException("Simulated transient failure during token revocation"))
                    .when(authTokenRepository)
                    .revokeAllActiveTokensByUserIdAndType(eq(targetActive.getId()), eq(TokenType.REFRESH));

            ApiException ex1 = assertThrows(ApiException.class, () ->
                    userInternalService.changeStatus(actor.getId(), targetActive.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED));

            assertEquals("STATUS_CHANGE_FAILED", ex1.getErrorCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex1.getStatus());
            assertFalse(ex1.getMessage().contains("restored"), "Message must not claim status was restored");
            assertEquals("The status was not changed because sessions could not be revoked. Please retry.", ex1.getMessage());

            // Target in DB is completely unchanged
            User persisted1 = userRepository.findById(targetActive.getId()).orElse(null);
            assertNotNull(persisted1);
            assertEquals(UserStatus.ACTIVE, persisted1.getStatus());
            assertEquals(3, persisted1.getFailedLoginCount(), "failedLoginCount must remain untouched");
            assertNull(persisted1.getLockStartedAt(), "lockStartedAt must remain null");

            // Token is unrevoked
            AuthToken storedToken1 = authTokenRepository.findByTokenHash("token-hash-f3-1").orElse(null);
            assertNotNull(storedToken1);
            assertFalse(storedToken1.isRevoked());

            // Retry succeeds
            reset(authTokenRepository);
            User updated1 = userInternalService.changeStatus(actor.getId(), targetActive.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
            assertNotNull(updated1);
            assertEquals(UserStatus.SUSPENDED, updated1.getStatus());

            User finalUser1 = userRepository.findById(targetActive.getId()).orElse(null);
            assertNotNull(finalUser1);
            assertEquals(UserStatus.SUSPENDED, finalUser1.getStatus());
            assertEquals(3, finalUser1.getFailedLoginCount());

            AuthToken finalToken1 = authTokenRepository.findByTokenHash("token-hash-f3-1").orElse(null);
            assertNotNull(finalToken1);
            assertTrue(finalToken1.isRevoked());
        } finally {
            reset(authTokenRepository);
        }

        // --- Case 2: Target is LOCKED with lockStartedAt set ---
        Instant lockTime = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        User targetLocked = createUserRecord("target-user-f3-2", Role.CUSTOMER, UserStatus.LOCKED);
        targetLocked.setFailedLoginCount(5);
        targetLocked.setLockStartedAt(lockTime);
        userRepository.save(targetLocked);

        AuthToken token2 = new AuthToken(
                targetLocked.getId(),
                TokenType.REFRESH,
                "token-hash-f3-2",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(token2);

        try {
            doThrow(new RuntimeException("Simulated transient failure during token revocation"))
                    .when(authTokenRepository)
                    .revokeAllActiveTokensByUserIdAndType(eq(targetLocked.getId()), eq(TokenType.REFRESH));

            ApiException ex2 = assertThrows(ApiException.class, () ->
                    userInternalService.changeStatus(actor.getId(), targetLocked.getId(), UserStatus.LOCKED, UserStatus.SUSPENDED));

            assertEquals("STATUS_CHANGE_FAILED", ex2.getErrorCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex2.getStatus());
            assertFalse(ex2.getMessage().contains("restored"));

            // LOCKED status and lockStartedAt remain intact
            User persisted2 = userRepository.findById(targetLocked.getId()).orElse(null);
            assertNotNull(persisted2);
            assertEquals(UserStatus.LOCKED, persisted2.getStatus(), "Status must remain LOCKED");
            assertEquals(5, persisted2.getFailedLoginCount());
            assertEquals(lockTime, persisted2.getLockStartedAt(), "lockStartedAt must remain intact");

            // Retry succeeds
            reset(authTokenRepository);
            User updated2 = userInternalService.changeStatus(actor.getId(), targetLocked.getId(), UserStatus.LOCKED, UserStatus.SUSPENDED);
            assertNotNull(updated2);
            assertEquals(UserStatus.SUSPENDED, updated2.getStatus());

            User finalUser2 = userRepository.findById(targetLocked.getId()).orElse(null);
            assertNotNull(finalUser2);
            assertEquals(UserStatus.SUSPENDED, finalUser2.getStatus());
        } finally {
            reset(authTokenRepository);
        }
    }

    @Test
    @DisplayName("F2 Real MongoDB Testcontainers: Guard lease expiry takeover during held operation preserves single active SUPER_ADMIN invariant")
    void testLeaseExpiryTakeoverDuringHeldOperationLeavesOneActiveSuperAdmin() throws Exception {
        User superAdminX = createUserRecord("super-x-lease", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.save(superAdminX);

        User superAdminY = createUserRecord("super-y-lease", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.save(superAdminY);

        AuthToken tokenX = new AuthToken(
                superAdminX.getId(),
                TokenType.REFRESH,
                "token-hash-x-lease",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(tokenX);

        AuthToken tokenY = new AuthToken(
                superAdminY.getId(),
                TokenType.REFRESH,
                "token-hash-y-lease",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(tokenY);

        CountDownLatch operationAHeld = new CountDownLatch(1);
        CountDownLatch proceedOperationA = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            // Hook into token revocation for superAdminX (Step 1 of Operation A) to pause Operation A
            doAnswer(invocation -> {
                operationAHeld.countDown();
                proceedOperationA.await(10, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(authTokenRepository).revokeAllActiveTokensByUserIdAndType(eq(superAdminX.getId()), eq(TokenType.REFRESH));

            // Launch Operation A: Actor Y tries to suspend X
            Future<?> futureA = executor.submit(() -> {
                authenticateAs(superAdminY.getId(), superAdminY.getEmail(), Role.SUPER_ADMIN);
                return userInternalService.changeStatus(superAdminY.getId(), superAdminX.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
            });

            // Wait until Operation A acquires the guard and is paused in Step 1
            assertTrue(operationAHeld.await(5, TimeUnit.SECONDS), "Operation A should reach Step 1 latch within timeout");

            // Inspect guard acquired by Operation A
            Document initialGuardDoc = mongoTemplate.findById("super-admin-status", Document.class, "auth_locks");
            assertNotNull(initialGuardDoc, "auth_locks document must exist");
            String ownerA = initialGuardDoc.getString("owner");
            Number fenceA = (Number) initialGuardDoc.get("fence");
            assertNotNull(ownerA);
            assertNotNull(fenceA);

            // Force guard expiry while Operation A is still held
            Query expireQuery = new Query(Criteria.where("_id").is("super-admin-status"));
            Update expireUpdate = new Update().set("expiresAt", Instant.now().minusSeconds(60));
            mongoTemplate.updateFirst(expireQuery, expireUpdate, "auth_locks");

            // Run Operation B on main thread: Actor X suspends Y
            authenticateAs(superAdminX.getId(), superAdminX.getEmail(), Role.SUPER_ADMIN);
            User updatedY = userInternalService.changeStatus(superAdminX.getId(), superAdminY.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
            assertNotNull(updatedY);
            assertEquals(UserStatus.SUSPENDED, updatedY.getStatus());

            // Release Operation A to proceed to Step 2
            proceedOperationA.countDown();

            // Operation A should fail at Proof point (c) because guard was overtaken/expired
            ExecutionException execEx = assertThrows(ExecutionException.class, () -> futureA.get(5, TimeUnit.SECONDS));
            Throwable cause = execEx.getCause();
            assertTrue(cause instanceof ApiException, "Cause should be ApiException but was " + cause);
            ApiException apiEx = (ApiException) cause;
            assertEquals("STATUS_CHANGE_FAILED", apiEx.getErrorCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, apiEx.getStatus());

            // Verify final state: exactly 1 active SUPER_ADMIN remains (X is ACTIVE, Y is SUSPENDED)
            User persistedX = userRepository.findById(superAdminX.getId()).orElse(null);
            assertNotNull(persistedX);
            assertEquals(UserStatus.ACTIVE, persistedX.getStatus(), "SuperAdmin X must remain ACTIVE");

            User persistedY = userRepository.findById(superAdminY.getId()).orElse(null);
            assertNotNull(persistedY);
            assertEquals(UserStatus.SUSPENDED, persistedY.getStatus(), "SuperAdmin Y must be SUSPENDED");

            long activeSuperAdmins = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            assertEquals(1L, activeSuperAdmins, "Exactly 1 active SUPER_ADMIN must remain");

            // Guard document in auth_locks is not owned by A, and fence has advanced
            Document finalGuardDoc = mongoTemplate.findById("super-admin-status", Document.class, "auth_locks");
            assertNotNull(finalGuardDoc);
            assertFalse(ownerA.equals(finalGuardDoc.getString("owner")), "Guard must no longer be owned by Operation A");
            Number finalFence = (Number) finalGuardDoc.get("fence");
            assertNotNull(finalFence);
            assertTrue(finalFence.longValue() > fenceA.longValue(), "Fence must have advanced past Operation A");
        } finally {
            proceedOperationA.countDown();
            executor.shutdownNow();
            reset(authTokenRepository);
        }
    }

    @Test
    @DisplayName("F2 Real MongoDB Testcontainers: Shortened lease naturally expires during delay and takeover leaves one active SUPER_ADMIN")
    void testShortenedLeaseNaturallyExpiresDuringDelayAndTakeoverLeavesOneActiveSuperAdmin() throws Exception {
        User superAdminX = createUserRecord("super-x-short", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.save(superAdminX);

        User superAdminY = createUserRecord("super-y-short", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        userRepository.save(superAdminY);

        AuthToken tokenX = new AuthToken(
                superAdminX.getId(),
                TokenType.REFRESH,
                "token-hash-x-short",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(tokenX);

        AuthToken tokenY = new AuthToken(
                superAdminY.getId(),
                TokenType.REFRESH,
                "token-hash-y-short",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        authTokenRepository.save(tokenY);

        UserInternalServiceImpl serviceImpl = org.springframework.test.util.AopTestUtils.getTargetObject(userInternalService);
        // Shorten lease to 300 ms, safety margin to 100 ms (maxAllowed = 200 ms)
        serviceImpl.setSuperAdminLockLeaseMs(300L);
        serviceImpl.setSuperAdminLockSafetyMarginMs(100L);

        CountDownLatch operationAHeld = new CountDownLatch(1);
        CountDownLatch proceedOperationA = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            doAnswer(invocation -> {
                operationAHeld.countDown();
                proceedOperationA.await(10, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(authTokenRepository).revokeAllActiveTokensByUserIdAndType(eq(superAdminX.getId()), eq(TokenType.REFRESH));

            Future<?> futureA = executor.submit(() -> {
                authenticateAs(superAdminY.getId(), superAdminY.getEmail(), Role.SUPER_ADMIN);
                return userInternalService.changeStatus(superAdminY.getId(), superAdminX.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
            });

            assertTrue(operationAHeld.await(5, TimeUnit.SECONDS), "Operation A should reach Step 1 latch within timeout");

            // Sleep past the 300ms lease duration so the lease naturally expires in real time
            Thread.sleep(400);

            // Operation B acquires the naturally expired lease without test intervention
            authenticateAs(superAdminX.getId(), superAdminX.getEmail(), Role.SUPER_ADMIN);
            User updatedY = userInternalService.changeStatus(superAdminX.getId(), superAdminY.getId(), UserStatus.ACTIVE, UserStatus.SUSPENDED);
            assertNotNull(updatedY);
            assertEquals(UserStatus.SUSPENDED, updatedY.getStatus());

            // Release Operation A
            proceedOperationA.countDown();

            // Operation A aborts with 503
            ExecutionException execEx = assertThrows(ExecutionException.class, () -> futureA.get(5, TimeUnit.SECONDS));
            Throwable cause = execEx.getCause();
            assertTrue(cause instanceof ApiException, "Cause should be ApiException but was " + cause);
            ApiException apiEx = (ApiException) cause;
            assertEquals("STATUS_CHANGE_FAILED", apiEx.getErrorCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, apiEx.getStatus());

            // Exactly 1 active SUPER_ADMIN remains
            User persistedX = userRepository.findById(superAdminX.getId()).orElse(null);
            assertNotNull(persistedX);
            assertEquals(UserStatus.ACTIVE, persistedX.getStatus(), "SuperAdmin X must remain ACTIVE");

            User persistedY = userRepository.findById(superAdminY.getId()).orElse(null);
            assertNotNull(persistedY);
            assertEquals(UserStatus.SUSPENDED, persistedY.getStatus(), "SuperAdmin Y must be SUSPENDED");

            long activeSuperAdmins = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            assertEquals(1L, activeSuperAdmins, "Exactly 1 active SUPER_ADMIN must remain");
        } finally {
            proceedOperationA.countDown();
            executor.shutdownNow();
            reset(authTokenRepository);
            serviceImpl.setSuperAdminLockLeaseMs(10000L);
            serviceImpl.setSuperAdminLockSafetyMarginMs(3000L);
        }
    }
}
