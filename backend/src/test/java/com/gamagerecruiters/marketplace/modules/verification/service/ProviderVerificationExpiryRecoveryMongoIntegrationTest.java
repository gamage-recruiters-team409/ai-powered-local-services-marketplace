package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.event.ProviderVerificationExpiredEvent;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

/**
 * Real MongoDB checks for WP02 verification-expiry event recovery.
 *
 * Uses its own Testcontainers MongoDB instance and database, without starting
 * the full Spring application or modifying other modules.
 *
 * A new service object models a worker restart; this does not restart the JVM
 * or guarantee delivery to an external notification system.
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class ProviderVerificationExpiryRecoveryMongoIntegrationTest {

    private static final String PROVIDER_ID = "expiry-recovery-provider-profile";
    private static final String PROVIDER_USER_ID = "expiry-recovery-provider-user";
    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final Duration GRACE_PERIOD = Duration.ofDays(10);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
    private static final List<String> DOCUMENT_IDS =
            List.of("document-id-expired", "document-id-valid");

    private static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7.0");

    private static MongoClient mongoClient;
    private static MongoTemplate mongoTemplate;
    private ProviderVerificationExpiryAtomicService atomicService;

    @BeforeAll
    static void startMongo() {
        MONGO.start();
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(
                mongoClient,
                "wp02_verification_expiry_recovery_tests"
        );
    }

    @AfterAll
    static void stopMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
        if (MONGO.isRunning()) {
            MONGO.stop();
        }
    }

    @BeforeEach
    void resetDatabase() {
        mongoTemplate.remove(new Query(), ProviderVerification.class);
        atomicService = new ProviderVerificationExpiryAtomicService(mongoTemplate);
    }

    @Test
    @DisplayName("Expiry status and pending event are stored together in MongoDB")
    void expiryPersistsPendingEventAtomically() {
        saveApprovedVerification();

        Optional<ProviderVerification> result = expireCurrentVerification();

        assertTrue(result.isPresent());
        assertEquals(ProviderVerificationStatus.EXPIRED, result.get().getStatus());
        assertTrue(persistedVerification().getBoolean("expiryEventPending"));
        assertNotNull(persistedVerification().get("expiredAt"));
        assertNotNull(persistedVerification().get("gracePeriodEndsAt"));
        assertEquals(List.of("document-id-expired"),
                persistedVerification().getList("expiredDocumentIds", String.class));
        assertNull(persistedVerification().get("expiryEventClaimToken"));
    }

    @Test
    @DisplayName("A changed document snapshot cannot be expired by a stale check")
    void staleSnapshotDoesNotChangeStatusOrCreateEvent() {
        saveApprovedVerification();

        Optional<ProviderVerification> result =
                atomicService.expireApprovedIfUnchanged(
                        PROVIDER_ID,
                        List.of("a-different-document"),
                        List.of("document-id-expired"),
                        NOW,
                        NOW.plus(GRACE_PERIOD)
                );

        assertTrue(result.isEmpty());
        assertEquals(ProviderVerificationStatus.APPROVED,
                mongoTemplate.findOne(
                        Query.query(Criteria.where("providerId").is(PROVIDER_ID)),
                        ProviderVerification.class
                ).getStatus());
        assertFalse(Boolean.TRUE.equals(
                persistedVerification().getBoolean("expiryEventPending")));
    }

    @Test
    @DisplayName("Only one concurrent worker can claim the same pending event")
    void concurrentWorkersCannotClaimTheSameEvent() throws Exception {
        saveApprovedVerification();
        assertTrue(expireCurrentVerification().isPresent());

        ProviderVerificationExpiryAtomicService secondWorker =
                new ProviderVerificationExpiryAtomicService(mongoTemplate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Optional<ProviderVerification>> first = executor.submit(() -> {
                start.await();
                return atomicService.claimNextPendingExpiryEvent(NOW, CLAIM_LEASE);
            });
            Future<Optional<ProviderVerification>> second = executor.submit(() -> {
                start.await();
                return secondWorker.claimNextPendingExpiryEvent(NOW, CLAIM_LEASE);
            });
            start.countDown();

            long successfulClaims = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            ).stream().filter(Optional::isPresent).count();

            assertEquals(1L, successfulClaims);
            assertTrue(persistedVerification().getBoolean("expiryEventPending"));
            assertNotNull(persistedVerification().get("expiryEventClaimToken"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Acknowledged events are not claimable again")
    void acknowledgedEventIsNotRepublished() {
        saveApprovedVerification();
        assertTrue(expireCurrentVerification().isPresent());

        ProviderVerification claimed =
                atomicService.claimNextPendingExpiryEvent(NOW, CLAIM_LEASE)
                        .orElseThrow();

        assertTrue(atomicService.markExpiryEventPublished(
                PROVIDER_ID, claimed.getExpiryEventClaimToken(), NOW));

        Document persisted = persistedVerification();
        assertFalse(persisted.getBoolean("expiryEventPending"));
        assertNotNull(persisted.get("expiryEventPublishedAt"));
        assertNull(persisted.get("expiryEventClaimToken"));
        assertNull(persisted.get("expiryEventLeaseUntil"));
        assertTrue(atomicService.claimNextPendingExpiryEvent(
                NOW.plus(Duration.ofDays(1)), CLAIM_LEASE).isEmpty());
    }

    @Test
    @DisplayName("Expired claim can be reclaimed; stale worker cannot acknowledge")
    void leaseExpiryAllowsReclaimWithoutStaleAcknowledgement() {
        saveApprovedVerification();
        assertTrue(expireCurrentVerification().isPresent());

        ProviderVerification firstClaim =
                atomicService.claimNextPendingExpiryEvent(NOW, CLAIM_LEASE)
                        .orElseThrow();
        String firstToken = firstClaim.getExpiryEventClaimToken();

        assertTrue(atomicService.claimNextPendingExpiryEvent(
                NOW.plusSeconds(30), CLAIM_LEASE).isEmpty());

        ProviderVerification secondClaim =
                atomicService.claimNextPendingExpiryEvent(
                        NOW.plus(CLAIM_LEASE).plusSeconds(1), CLAIM_LEASE)
                        .orElseThrow();
        String secondToken = secondClaim.getExpiryEventClaimToken();

        assertNotEquals(firstToken, secondToken);
        assertFalse(atomicService.markExpiryEventPublished(
                PROVIDER_ID, firstToken, NOW.plusSeconds(121)));
        assertTrue(persistedVerification().getBoolean("expiryEventPending"));
        assertTrue(atomicService.markExpiryEventPublished(
                PROVIDER_ID, secondToken, NOW.plusSeconds(121)));
        assertFalse(persistedVerification().getBoolean("expiryEventPending"));
    }

    @Test
    @DisplayName("Failed publication remains pending and is delivered by a later worker")
    void failedPublicationRetriesAfterNewServiceInstance() {
        saveApprovedVerification();
        assertTrue(expireCurrentVerification().isPresent());

        ProviderVerificationRepository verificationRepository =
                mock(ProviderVerificationRepository.class);
        VerificationDocumentRepository documentRepository =
                mock(VerificationDocumentRepository.class);
        ProviderProfileRepository profileRepository =
                mock(ProviderProfileRepository.class);
        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);

        when(verificationRepository.findAllByStatusInOrderBySubmittedAtAsc(
                List.of(ProviderVerificationStatus.APPROVED)))
                .thenReturn(List.of());

        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId(PROVIDER_USER_ID);
        when(profileRepository.findById(PROVIDER_ID))
                .thenReturn(Optional.of(profile));

        doThrow(new IllegalStateException("simulated publication failure"))
                .doNothing()
                .when(eventPublisher)
                .publishEvent(any(ProviderVerificationExpiredEvent.class));

        ProviderVerificationExpiryServiceImpl firstWorker = newExpiryService(
                verificationRepository, documentRepository, profileRepository,
                eventPublisher, NOW);

        assertEquals(0, firstWorker.processExpiredVerifications());
        assertTrue(persistedVerification().getBoolean("expiryEventPending"));
        assertNull(persistedVerification().get("expiryEventClaimToken"));
        assertNotNull(persistedVerification().get("expiryEventLeaseUntil"));

        // A second invocation before retry time cannot publish again.
        assertEquals(0, firstWorker.processExpiredVerifications());
        verify(eventPublisher, times(1)).publishEvent(
                any(ProviderVerificationExpiredEvent.class));

        // Create a NEW service and atomic service, reading the same MongoDB data.
        ProviderVerificationExpiryServiceImpl restartedWorker = newExpiryService(
                verificationRepository, documentRepository, profileRepository,
                eventPublisher, NOW.plusSeconds(61));

        assertEquals(0, restartedWorker.processExpiredVerifications());
        verify(eventPublisher, times(2)).publishEvent(
                any(ProviderVerificationExpiredEvent.class));
        assertFalse(persistedVerification().getBoolean("expiryEventPending"));
        assertNotNull(persistedVerification().get("expiryEventPublishedAt"));

        assertEquals(0, restartedWorker.processExpiredVerifications());
        verify(eventPublisher, times(2)).publishEvent(
                any(ProviderVerificationExpiredEvent.class));
    }

    @Test
    @DisplayName("Old expiry details are not cleared while an expiry event is pending")
    void pendingEventPreventsPrematureMetadataCleanup() {
        saveApprovedVerification();
        assertTrue(expireCurrentVerification().isPresent());

        // Simulate subsequent approval while an expiry notification is pending.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("providerId").is(PROVIDER_ID)),
                new Update().set("status", ProviderVerificationStatus.APPROVED),
                ProviderVerification.class
        );

        assertFalse(atomicService.clearExpiryMetadataIfApproved(PROVIDER_ID));
        assertNotNull(persistedVerification().get("expiredAt"));

        ProviderVerification claim = atomicService.claimNextPendingExpiryEvent(
                NOW, CLAIM_LEASE).orElseThrow();
        assertTrue(atomicService.markExpiryEventPublished(
                PROVIDER_ID, claim.getExpiryEventClaimToken(), NOW));

        assertTrue(atomicService.clearExpiryMetadataIfApproved(PROVIDER_ID));
        assertNull(persistedVerification().get("expiredAt"));
        assertNull(persistedVerification().get("gracePeriodEndsAt"));
        assertNull(persistedVerification().get("expiredDocumentIds"));
    }

    private void saveApprovedVerification() {
        ProviderVerification verification = new ProviderVerification();
        verification.setId("verification-for-event-recovery");
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(ProviderVerificationStatus.APPROVED);
        verification.setSubmittedDocumentIds(DOCUMENT_IDS);
        verification.setCoordinationRevision(5L);
        verification.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        verification.setUpdatedAt(NOW.minus(Duration.ofDays(1)));
        mongoTemplate.save(verification);
    }

    private Optional<ProviderVerification> expireCurrentVerification() {
        return atomicService.expireApprovedIfUnchanged(
                PROVIDER_ID,
                DOCUMENT_IDS,
                List.of("document-id-expired"),
                NOW,
                NOW.plus(GRACE_PERIOD)
        );
    }

    private Document persistedVerification() {
        Document result = mongoTemplate.getCollection("provider_verifications")
                .find(new Document("providerId", PROVIDER_ID))
                .first();
        assertNotNull(result, "Expected verification record in MongoDB");
        return result;
    }

    private ProviderVerificationExpiryServiceImpl newExpiryService(
            ProviderVerificationRepository verificationRepository,
            VerificationDocumentRepository documentRepository,
            ProviderProfileRepository profileRepository,
            ApplicationEventPublisher eventPublisher,
            Instant now
    ) {
        return new ProviderVerificationExpiryServiceImpl(
                verificationRepository,
                documentRepository,
                profileRepository,
                new ProviderVerificationExpiryAtomicService(mongoTemplate),
                eventPublisher,
                Clock.fixed(now, ZoneId.of("Asia/Colombo")),
                GRACE_PERIOD
        );
    }
}
