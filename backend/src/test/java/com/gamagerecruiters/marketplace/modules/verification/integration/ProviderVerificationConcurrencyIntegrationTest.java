
package com.gamagerecruiters.marketplace.modules.verification.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.cloudinary.Cloudinary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationService;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationAdminService;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationAtomicUpdates;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
class ProviderVerificationConcurrencyIntegrationTest {

    private static final String PROVIDER_ID = "provider-integration-1";
    private static final String PROVIDER_USER_ID = "provider-user-integration-1";

    private static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        if (!MONGO.isRunning()) {
            MONGO.start();
        }
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    @BeforeAll
    static void startMongo() {
        if (!MONGO.isRunning()) {
            MONGO.start();
        }
    }

    @AfterAll
    static void stopMongo() {
        if (MONGO.isRunning()) {
            MONGO.stop();
        }
    }

    @Autowired
    private ProviderVerificationRepository verificationRepository;

    @Autowired
    private VerificationDocumentRepository documentRepository;

    @Autowired
    private ProviderProfileRepository providerProfileRepository;

    @Autowired
    private VerificationAtomicUpdates atomicUpdates;

    @Autowired
    private ProviderVerificationService providerVerificationService;

    @Autowired
    private VerificationAdminService verificationAdminService;

    // These tests exercise MongoDB workflow coordination, not Cloudinary itself.
    @MockitoBean
    private VerificationFileStorageService verificationFileStorageService;

    @MockitoBean
    private Cloudinary cloudinary;

    @BeforeEach
    void cleanDatabase() {
        documentRepository.deleteAll();
        verificationRepository.deleteAll();
        providerProfileRepository.deleteAll();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A failed document update rolls back the Admin verification decision")
    void adminDecisionRollsBackWhenSubmittedDocumentIsMissing() {
        saveVerification(
                ProviderVerificationStatus.UNDER_REVIEW,
                List.of("document-present", "document-missing")
        );
        documentRepository.save(document("document-present"));

        authenticate(Role.ADMIN, "admin-rollback");

        assertThrows(
                IllegalStateException.class,
                () -> verificationAdminService.approveVerification(PROVIDER_ID)
        );

        ProviderVerification stored = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        VerificationDocument storedDocument = documentRepository
                .findByIdAndProviderId("document-present", PROVIDER_ID)
                .orElseThrow();

        assertEquals(ProviderVerificationStatus.UNDER_REVIEW, stored.getStatus());
        assertEquals(VerificationDocumentStatus.SUBMITTED, storedDocument.getStatus());
    }

    // CATEGORY RE-VERIFICATION INTEGRATION TESTS

    @Test
    @DisplayName("Adding a category requests re-verification for an approved provider")
    void categoryReverificationChangesApprovedVerification() {

        List<String> submittedDocumentIds = List.of("document-1");

        saveVerification(
                ProviderVerificationStatus.APPROVED,
                submittedDocumentIds
        );

        Instant reviewedAt =
                Instant.parse("2026-11-17T10:00:00Z");

        ProviderVerification approved = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        approved.setReviewedAt(reviewedAt);
        approved.setReviewedByUserId("admin-1");
        approved.setDecisionReason("Original approval");

        verificationRepository.save(approved);

        Instant requestedAt = Instant.now();

        var result = atomicUpdates.requestCategoryReverification(
                PROVIDER_ID,
                requestedAt
        );

        assertTrue(
                result.isPresent(),
                "An approved provider should be moved to resubmission"
        );

        ProviderVerification stored = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(
                ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                stored.getStatus()
        );

        assertEquals(
                "A new service category was added. Please upload updated verification documents and resubmit.",
                stored.getDecisionReason()
        );

        // The original Admin review information must be preserved.
        assertEquals(reviewedAt, stored.getReviewedAt());
        assertEquals("admin-1", stored.getReviewedByUserId());

        // The previous submitted document snapshot must be preserved.
        assertEquals(
                submittedDocumentIds,
                stored.getSubmittedDocumentIds()
        );

        // The workflow revision must increment exactly once.
        assertEquals(1L, stored.getCoordinationRevision());
    }

    @Test
    @DisplayName("Category re-verification does not change a non-approved provider")
    void categoryReverificationDoesNotChangeNonApprovedVerification() {

        List<String> submittedDocumentIds = List.of("document-1");

        saveVerification(
                ProviderVerificationStatus.UNDER_REVIEW,
                submittedDocumentIds
        );

        ProviderVerification beforeUpdate = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        Instant previousUpdatedAt = beforeUpdate.getUpdatedAt();

        var result = atomicUpdates.requestCategoryReverification(
                PROVIDER_ID,
                Instant.now()
        );

        assertTrue(
                result.isEmpty(),
                "A non-approved provider must not be moved to resubmission"
        );

        ProviderVerification stored = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(
                ProviderVerificationStatus.UNDER_REVIEW,
                stored.getStatus()
        );

        assertEquals(
                submittedDocumentIds,
                stored.getSubmittedDocumentIds()
        );

        assertEquals(0L, stored.getCoordinationRevision());

        assertEquals(
                previousUpdatedAt,
                stored.getUpdatedAt(),
                "A non-approved verification record must remain unchanged"
        );
    }

    @Test
    @DisplayName("Two conflicting Admin decisions cannot both succeed")
    void concurrentAdminDecisionsAllowOnlyOneWinner() throws Exception {
        saveVerification(
                ProviderVerificationStatus.UNDER_REVIEW,
                List.of("document-1")
        );
        documentRepository.save(document("document-1"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> approve = pool.submit(() -> {
                start.await();
                return attemptDecision("admin-approve", true);
            });

            Future<Boolean> reject = pool.submit(() -> {
                start.await();
                return attemptDecision("admin-reject", false);
            });

            start.countDown();

            boolean approveSucceeded = approve.get(20, TimeUnit.SECONDS);
            boolean rejectSucceeded = reject.get(20, TimeUnit.SECONDS);

            assertNotEquals(
                    approveSucceeded,
                    rejectSucceeded,
                    "Exactly one competing decision must succeed"
            );

            ProviderVerification stored = verificationRepository
                    .findByProviderId(PROVIDER_ID)
                    .orElseThrow();

            VerificationDocument storedDocument = documentRepository
                    .findByIdAndProviderId("document-1", PROVIDER_ID)
                    .orElseThrow();

            if (stored.getStatus() == ProviderVerificationStatus.APPROVED) {
                assertEquals(VerificationDocumentStatus.APPROVED, storedDocument.getStatus());
            } else {
                assertEquals(ProviderVerificationStatus.REJECTED, stored.getStatus());
                assertEquals(VerificationDocumentStatus.REJECTED, storedDocument.getStatus());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A stale start-review request cannot overwrite a completed decision")
    void staleStartReviewCannotOverwriteDecision() {
        saveVerification(
                ProviderVerificationStatus.SUBMITTED,
                List.of("document-1")
        );
        documentRepository.save(document("document-1"));

        authenticate(Role.ADMIN, "admin-decision");
        verificationAdminService.rejectVerification(
                PROVIDER_ID,
                "Document is not valid"
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> verificationAdminService.startReview(PROVIDER_ID)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                ProviderVerificationStatus.REJECTED,
                verificationRepository.findByProviderId(PROVIDER_ID)
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    @DisplayName("Upload reservation and submission cannot pass each other")
    void concurrentUploadReservationAndSubmissionAreCoordinated() throws Exception {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId(PROVIDER_USER_ID);
        profile.setDisplayName("Integration Provider");
        providerProfileRepository.save(profile);

        saveVerification(ProviderVerificationStatus.NOT_SUBMITTED, List.of());
        documentRepository.save(document("existing-document"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<String> uploadReservation = pool.submit(() -> {
                start.await();
                try {
                    return atomicUpdates.reserveDocumentUpload(
                            PROVIDER_ID,
                            Instant.now()
                    );
                } catch (ResponseStatusException exception) {
                    return null;
                }
            });

            Future<Boolean> submission = pool.submit(() -> {
                start.await();
                authenticate(Role.PROVIDER, PROVIDER_USER_ID);
                try {
                    providerVerificationService.submitMyVerification();
                    return true;
                } catch (ResponseStatusException exception) {
                    return false;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            start.countDown();

            String reservationId = uploadReservation.get(20, TimeUnit.SECONDS);
            boolean submitted = submission.get(20, TimeUnit.SECONDS);
            boolean uploadReserved = reservationId != null;

            assertNotEquals(
                    uploadReserved,
                    submitted,
                    "Either the upload reservation or submission must win, never both"
            );

            if (uploadReserved) {
                atomicUpdates.releaseDocumentUpload(
                        PROVIDER_ID,
                        reservationId
                );

                ProviderVerification stored = verificationRepository
                        .findByProviderId(PROVIDER_ID)
                        .orElseThrow();

                assertEquals(ProviderVerificationStatus.NOT_SUBMITTED, stored.getStatus());
                assertEquals(0, stored.getInFlightUploadCount());
            } else {
                ProviderVerification stored = verificationRepository
                        .findByProviderId(PROVIDER_ID)
                        .orElseThrow();

                assertEquals(ProviderVerificationStatus.SUBMITTED, stored.getStatus());
                assertEquals(List.of("existing-document"), stored.getSubmittedDocumentIds());

                assertThrows(
                        ResponseStatusException.class,
                        () -> atomicUpdates.reserveDocumentUpload(
                                PROVIDER_ID,
                                Instant.now()
                        )
                );
            }
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("Normal upload completion releases only its own reservation")
    void normalUploadReleaseUsesReservationIdentity() {
        Instant now = Instant.now();

        String firstReservation =
                atomicUpdates.reserveDocumentUpload(PROVIDER_ID, now);

        String secondReservation =
                atomicUpdates.reserveDocumentUpload(PROVIDER_ID, now.plusMillis(1));

        ProviderVerification withTwoReservations = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(2, withTwoReservations.getInFlightUploadCount());
        assertEquals(2, withTwoReservations.getUploadReservations().size());

        atomicUpdates.releaseDocumentUpload(PROVIDER_ID, firstReservation);

        ProviderVerification afterFirstRelease = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(1, afterFirstRelease.getInFlightUploadCount());
        assertEquals(1, afterFirstRelease.getUploadReservations().size());
        assertEquals(
                secondReservation,
                afterFirstRelease.getUploadReservations().get(0).getReservationId()
        );

        // A repeated release must not decrement another upload's reservation.
        atomicUpdates.releaseDocumentUpload(PROVIDER_ID, firstReservation);

        ProviderVerification afterRepeatedRelease = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(1, afterRepeatedRelease.getInFlightUploadCount());

        atomicUpdates.releaseDocumentUpload(PROVIDER_ID, secondReservation);

        ProviderVerification afterBothReleased = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(0, afterBothReleased.getInFlightUploadCount());
        assertTrue(afterBothReleased.getUploadReservations().isEmpty());
    }

    @Test
    @DisplayName("Expired reservations are recovered after a backend restart")
    void expiredUploadReservationIsRecovered() {
        Instant uploadStartedAt = Instant.now().minus(Duration.ofMinutes(10));

        atomicUpdates.reserveDocumentUpload(PROVIDER_ID, uploadStartedAt);

        ProviderVerification beforeRecovery = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(1, beforeRecovery.getInFlightUploadCount());

        int recovered = atomicUpdates.recoverExpiredUploadReservations(
                PROVIDER_ID,
                Instant.now()
        );

        ProviderVerification afterRecovery = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(1, recovered);
        assertEquals(0, afterRecovery.getInFlightUploadCount());
        assertTrue(afterRecovery.getUploadReservations().isEmpty());
    }

    @Test
    @DisplayName("An active upload reservation is not released by recovery")
    void activeUploadReservationIsProtectedFromRecovery() {
        Instant uploadStartedAt = Instant.now();

        String reservationId =
                atomicUpdates.reserveDocumentUpload(
                        PROVIDER_ID,
                        uploadStartedAt
                );

        Instant beforeLeaseExpiry =
                uploadStartedAt.plus(Duration.ofMinutes(4));

        int recovered = atomicUpdates.recoverExpiredUploadReservations(
                PROVIDER_ID,
                beforeLeaseExpiry
        );

        ProviderVerification stored = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(0, recovered);
        assertEquals(1, stored.getInFlightUploadCount());
        assertEquals(1, stored.getUploadReservations().size());
        assertEquals(
                reservationId,
                stored.getUploadReservations().get(0).getReservationId()
        );

        // An active upload can renew its lease before it expires.
        assertTrue(
                atomicUpdates.renewDocumentUploadReservation(
                        PROVIDER_ID,
                        reservationId,
                        beforeLeaseExpiry
                )
        );

        // The original expiry has passed, but the renewed lease is still active.
        int recoveredAfterRenewal =
                atomicUpdates.recoverExpiredUploadReservations(
                        PROVIDER_ID,
                        uploadStartedAt.plus(Duration.ofMinutes(8))
                );

        ProviderVerification afterRenewal = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(0, recoveredAfterRenewal);
        assertEquals(1, afterRenewal.getInFlightUploadCount());
        assertEquals(1, afterRenewal.getUploadReservations().size());
    }

    @Test
    @DisplayName("Provider can submit successfully after an abandoned reservation is recovered")
    void providerCanSubmitAfterExpiredReservationRecovery() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId(PROVIDER_USER_ID);
        profile.setDisplayName("Integration Provider");
        providerProfileRepository.save(profile);

        Instant abandonedUploadStartedAt =
                Instant.now().minus(Duration.ofMinutes(10));

        atomicUpdates.reserveDocumentUpload(
                PROVIDER_ID,
                abandonedUploadStartedAt
        );

        documentRepository.save(document("existing-document"));

        authenticate(Role.PROVIDER, PROVIDER_USER_ID);

        var response = providerVerificationService.submitMyVerification();

        ProviderVerification stored = verificationRepository
                .findByProviderId(PROVIDER_ID)
                .orElseThrow();

        assertEquals(ProviderVerificationStatus.SUBMITTED, response.getStatus());
        assertEquals(ProviderVerificationStatus.SUBMITTED, stored.getStatus());
        assertEquals(0, stored.getInFlightUploadCount());
        assertTrue(stored.getUploadReservations().isEmpty());
        assertEquals(
                List.of("existing-document"),
                stored.getSubmittedDocumentIds()
        );

        SecurityContextHolder.clearContext();
    }

    private boolean attemptDecision(String adminId, boolean approve) {
        authenticate(Role.ADMIN, adminId);
        try {
            if (approve) {
                verificationAdminService.approveVerification(PROVIDER_ID);
            } else {
                verificationAdminService.rejectVerification(
                        PROVIDER_ID,
                        "Rejected by concurrent integration test"
                );
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void saveVerification(
            ProviderVerificationStatus status,
            List<String> submittedDocumentIds
    ) {
        ProviderVerification verification = new ProviderVerification();
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(status);
        verification.setSubmittedAt(Instant.now().minusSeconds(60));
        verification.setCreatedAt(Instant.now().minusSeconds(120));
        verification.setUpdatedAt(Instant.now());
        verification.setSubmittedDocumentIds(submittedDocumentIds);
        verification.setCoordinationRevision(0L);
        verification.setInFlightUploadCount(0);
        verificationRepository.save(verification);
    }

    private VerificationDocument document(String id) {
        VerificationDocument document = new VerificationDocument();
        document.setId(id);
        document.setProviderId(PROVIDER_ID);
        document.setDocumentType(VerificationDocumentType.ID);
        document.setStorageKey("private-" + id);
        document.setAssetId("asset-" + id);
        document.setResourceType("image");
        document.setDeliveryType("authenticated");
        document.setOriginalFileName(id + ".pdf");
        document.setFormat("pdf");
        document.setBytes(1000L);
        document.setStatus(VerificationDocumentStatus.SUBMITTED);
        document.setUploadedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
    }

    private void authenticate(Role role, String userId) {
        UserPrincipal principal = new UserPrincipal(
                userId,
                userId + "@example.com",
                role
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );
    }
}
