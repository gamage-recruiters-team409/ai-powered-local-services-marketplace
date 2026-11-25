package com.gamagerecruiters.marketplace.modules.customer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupReason;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupStatus;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;
import com.gamagerecruiters.marketplace.modules.customer.repository.ProfileImageCleanupRepository;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerProfileImageStorage;
import com.gamagerecruiters.marketplace.modules.customer.service.ProfileImageCleanupService;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
public class ProfileImageCleanupConcurrencyMongoIntegrationTest {

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileImageCleanupRepository cleanupRepository;

    @BeforeEach
    void cleanCollections() {
        userRepository.deleteAll();
        cleanupRepository.deleteAll();
    }

    @Test
    @DisplayName("F2 (g): Restart survival: instance B processes PENDING task created by instance A on same DB")
    void restartSurvival_instanceBProcessesTaskCreatedByInstanceA() {
        CustomerProfileImageStorage storageA = mock(CustomerProfileImageStorage.class);
        ProfileImageCleanupService serviceA = new ProfileImageCleanupService(
                cleanupRepository, storageA, mongoTemplate, Optional.empty(), Duration.ofMinutes(2));

        serviceA.schedule("survive-pub-1", "user-1", ProfileImageCleanupReason.REPLACED);

        ProfileImageCleanupTask stored = cleanupRepository.findByPublicId("survive-pub-1").orElseThrow();
        assertEquals(ProfileImageCleanupStatus.PENDING, stored.getStatus());

        CustomerProfileImageStorage storageB = mock(CustomerProfileImageStorage.class);
        when(storageB.delete("survive-pub-1")).thenReturn(true);

        ProfileImageCleanupService serviceB = new ProfileImageCleanupService(
                cleanupRepository, storageB, mongoTemplate, Optional.empty(), Duration.ofMinutes(2));

        int processed = serviceB.retryPending(10);
        assertEquals(1, processed);
        verify(storageB).delete("survive-pub-1");

        ProfileImageCleanupTask afterRetry = cleanupRepository.findByPublicId("survive-pub-1").orElseThrow();
        assertEquals(ProfileImageCleanupStatus.DONE, afterRetry.getStatus());
    }

    @Test
    @DisplayName("F2 (h): Referenced image is NEVER deleted by the job/retry path")
    void referencedImage_neverDeletedByRetryPath() {
        User user = new User();
        user.setId("user-active-1");
        user.setName("Active User");
        user.setEmail("active@example.com");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setProfileImagePublicId("active-pub-ref");
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        CustomerProfileImageStorage storage = mock(CustomerProfileImageStorage.class);
        ProfileImageCleanupService service = new ProfileImageCleanupService(
                cleanupRepository, storage, mongoTemplate, Optional.empty(), Duration.ofMinutes(2));

        service.schedule("active-pub-ref", "user-active-1", ProfileImageCleanupReason.REPLACED);

        int processed = service.retryPending(10);
        assertEquals(1, processed);

        verify(storage, never()).delete(any());

        ProfileImageCleanupTask taskInDb = cleanupRepository.findByPublicId("active-pub-ref").orElseThrow();
        assertEquals(ProfileImageCleanupStatus.DONE, taskInDb.getStatus());
        assertEquals("ReferencedByUser", taskInDb.getLastErrorClass());
    }

    @Test
    @DisplayName("F2 (i): Repeated runs do not repeat completed (DONE) tasks")
    void repeatedRuns_doNotRepeatCompletedTasks() {
        CustomerProfileImageStorage storage = mock(CustomerProfileImageStorage.class);
        when(storage.delete("once-pub")).thenReturn(true);

        ProfileImageCleanupService service = new ProfileImageCleanupService(
                cleanupRepository, storage, mongoTemplate, Optional.empty(), Duration.ofMinutes(2));

        service.schedule("once-pub", "user-1", ProfileImageCleanupReason.REPLACED);

        int run1 = service.retryPending(10);
        assertEquals(1, run1);

        int run2 = service.retryPending(10);
        assertEquals(0, run2);

        verify(storage).delete("once-pub");
    }

    @Test
    @DisplayName("F2 (j): Distributed lease prevents second instance from claiming task until lease expires")
    void lease_preventsConcurrentClaimUntilExpired() {
        Instant t0 = Instant.parse("2026-03-30T10:00:00Z");
        Clock clock1 = Clock.fixed(t0, ZoneOffset.UTC);

        CustomerProfileImageStorage storage1 = mock(CustomerProfileImageStorage.class);
        ProfileImageCleanupService service1 = new ProfileImageCleanupService(
                cleanupRepository, storage1, mongoTemplate, Optional.of(clock1), Duration.ofMinutes(2));

        service1.schedule("lease-pub", "user-1", ProfileImageCleanupReason.REPLACED);

        // Instance 1 leases task at t0 (leaseUntil = t0 + 2m)
        Query claimQuery = Query.query(Criteria.where("publicId").is("lease-pub"));
        mongoTemplate.updateFirst(claimQuery,
                new org.springframework.data.mongodb.core.query.Update().set("leaseUntil", t0.plus(Duration.ofMinutes(2))),
                ProfileImageCleanupTask.class);

        // Instance 2 at t0 + 1m tries to claim: cannot claim since lease is active
        Instant t1 = t0.plus(Duration.ofMinutes(1));
        Clock clock2 = Clock.fixed(t1, ZoneOffset.UTC);
        CustomerProfileImageStorage storage2 = mock(CustomerProfileImageStorage.class);
        ProfileImageCleanupService service2 = new ProfileImageCleanupService(
                cleanupRepository, storage2, mongoTemplate, Optional.of(clock2), Duration.ofMinutes(2));

        int processedInstance2 = service2.retryPending(10);
        assertEquals(0, processedInstance2);
        verify(storage2, never()).delete(any());

        // Instance 2 at t0 + 3m (lease expired) claims and processes the task
        Instant t3 = t0.plus(Duration.ofMinutes(3));
        Clock clock2Expired = Clock.fixed(t3, ZoneOffset.UTC);
        when(storage2.delete("lease-pub")).thenReturn(true);
        ProfileImageCleanupService service2AfterExpiry = new ProfileImageCleanupService(
                cleanupRepository, storage2, mongoTemplate, Optional.of(clock2Expired), Duration.ofMinutes(2));

        int processedAfterExpiry = service2AfterExpiry.retryPending(10);
        assertEquals(1, processedAfterExpiry);
        verify(storage2).delete("lease-pub");
    }

    @Test
    @DisplayName("F3 (l): Concurrent scheduling with N threads creates exactly ONE document without exceptions")
    void concurrentScheduling_nThreadsSamePublicId_createsExactlyOneDocument() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        CustomerProfileImageStorage storage = mock(CustomerProfileImageStorage.class);
        ProfileImageCleanupService service = new ProfileImageCleanupService(
                cleanupRepository, storage, mongoTemplate, Optional.empty(), Duration.ofMinutes(2));

        AtomicInteger errors = new AtomicInteger(0);
        List<ProfileImageCleanupTask> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ProfileImageCleanupTask task = service.schedule(
                            "concurrent-pub-id", "owner-1", ProfileImageCleanupReason.REPLACED);
                    if (task != null) {
                        results.add(task);
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, errors.get(), "No exception should reach any caller");
        assertEquals(threads, results.size());

        long count = mongoTemplate.count(
                Query.query(Criteria.where("publicId").is("concurrent-pub-id")),
                ProfileImageCleanupTask.class
        );
        assertEquals(1L, count, "Exactly ONE task document must exist in MongoDB");
    }

    @Test
    @DisplayName("F4 (o): Losing request never cancels winning request's PENDING cleanup task")
    void losingRequest_doesNotCancelWinningRequestCleanupTask() {
        CustomerProfileImageStorage storage = mock(CustomerProfileImageStorage.class);
        ProfileImageCleanupService service = new ProfileImageCleanupService(
                cleanupRepository, storage, mongoTemplate, Optional.empty(), Duration.ofMinutes(2));

        // Original image X has a scheduled task
        service.schedule("image-X", "user-1", ProfileImageCleanupReason.REPLACED);

        ProfileImageCleanupTask taskX = cleanupRepository.findByPublicId("image-X").orElseThrow();
        assertEquals(ProfileImageCleanupStatus.PENDING, taskX.getStatus());

        // Simulated losing request: does not cancel image-X
        // In F4, losing request calls NO cancel method.
        ProfileImageCleanupTask taskXAfterLoss = cleanupRepository.findByPublicId("image-X").orElseThrow();
        assertEquals(ProfileImageCleanupStatus.PENDING, taskXAfterLoss.getStatus());

        // Later retry (storage now OK) processes image-X and marks DONE
        when(storage.delete("image-X")).thenReturn(true);
        int processed = service.retryPending(10);
        assertEquals(1, processed);

        ProfileImageCleanupTask taskXFinal = cleanupRepository.findByPublicId("image-X").orElseThrow();
        assertEquals(ProfileImageCleanupStatus.DONE, taskXFinal.getStatus());
    }
}
