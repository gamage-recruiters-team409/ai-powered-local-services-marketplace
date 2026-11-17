package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentAccessResponse;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class VerificationAdminServiceImplTest {

    private static final String PROVIDER_ID = "provider-123";
    private static final String ADMIN_ID = "admin-123";
    private static final String DOCUMENT_ID = "doc-1";
    private static final String ACCESS_URL = "https://secure.example.com/verification-document";

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private VerificationFileStorageService verificationFileStorageService;

    @Mock
    private VerificationAtomicUpdates verificationAtomicUpdates;

    @InjectMocks
    private VerificationAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        setAdmin(ADMIN_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getPendingVerifications_returnsQueueItems() {
        ProviderVerification verification = verification(
                ProviderVerificationStatus.SUBMITTED,
                List.of("doc-1", "doc-2")
        );

        when(providerVerificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(any()))
                .thenReturn(List.of(verification));
        when(providerProfileRepository.findById(PROVIDER_ID))
                .thenReturn(Optional.of(profile()));
        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document("doc-1"), document("doc-2")));

        var result = service.getPendingVerifications();

        assertEquals(1, result.size());
        assertEquals(PROVIDER_ID, result.get(0).providerId());
        assertEquals(2, result.get(0).documentCount());
    }

    @Test
    void getVerificationDetails_returnsProviderAndDocuments() {
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification(
                        ProviderVerificationStatus.SUBMITTED,
                        List.of(DOCUMENT_ID)
                )));
        when(providerProfileRepository.findById(PROVIDER_ID))
                .thenReturn(Optional.of(profile()));
        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document(DOCUMENT_ID)));

        var result = service.getVerificationDetails(PROVIDER_ID);

        assertEquals(PROVIDER_ID, result.providerId());
        assertEquals("Test Provider", result.displayName());
        assertEquals(1, result.documents().size());
    }

    @Test
    void getVerificationDocumentAccess_returnsShortLivedPrivateUrl() {
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification(
                        ProviderVerificationStatus.SUBMITTED,
                        List.of(DOCUMENT_ID)
                )));
        when(verificationDocumentRepository.findByIdAndProviderId(
                DOCUMENT_ID, PROVIDER_ID
        )).thenReturn(Optional.of(document(DOCUMENT_ID)));
        when(verificationFileStorageService.generateTemporaryAccessUrl(
                eq("private-public-id-" + DOCUMENT_ID),
                eq("pdf"),
                eq("image"),
                eq("authenticated"),
                any(Instant.class)
        )).thenReturn(ACCESS_URL);

        VerificationDocumentAccessResponse response =
                service.getVerificationDocumentAccess(PROVIDER_ID, DOCUMENT_ID);

        assertEquals(DOCUMENT_ID, response.documentId());
        assertEquals(ACCESS_URL, response.accessUrl());
        assertNotNull(response.expiresAt());
    }

    @Test
    void getVerificationDocumentAccess_rejectsDocumentFromAnotherProvider() {
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification(
                        ProviderVerificationStatus.SUBMITTED,
                        List.of(DOCUMENT_ID)
                )));
        when(verificationDocumentRepository.findByIdAndProviderId(
                DOCUMENT_ID, PROVIDER_ID
        )).thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.getVerificationDocumentAccess(PROVIDER_ID, DOCUMENT_ID)
        );

        verifyNoInteractions(verificationFileStorageService);
    }

    @Test
    void startReview_usesAtomicConditionalUpdate() {
        ProviderVerification updated = verification(
                ProviderVerificationStatus.UNDER_REVIEW,
                List.of(DOCUMENT_ID)
        );

        when(verificationAtomicUpdates.startReview(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(Optional.of(updated));

        var response = service.startReview(PROVIDER_ID);

        assertEquals(ProviderVerificationStatus.UNDER_REVIEW, response.getStatus());
        verify(verificationAtomicUpdates).startReview(
                eq(PROVIDER_ID), any(Instant.class)
        );
    }

    @Test
    void startReview_isIdempotentWhenAlreadyUnderReview() {
        ProviderVerification existing = verification(
                ProviderVerificationStatus.UNDER_REVIEW,
                List.of(DOCUMENT_ID)
        );

        when(verificationAtomicUpdates.startReview(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(Optional.empty());
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        var response = service.startReview(PROVIDER_ID);

        assertEquals(ProviderVerificationStatus.UNDER_REVIEW, response.getStatus());
    }

    @Test
    void approveVerification_updatesOnlyFrozenSubmittedDocuments() {
        List<String> ids = List.of("doc-1", "doc-2");
        ProviderVerification current = verification(
                ProviderVerificationStatus.UNDER_REVIEW, ids
        );
        ProviderVerification approved = verification(
                ProviderVerificationStatus.APPROVED, ids
        );
        approved.setReviewedByUserId(ADMIN_ID);
        approved.setReviewedAt(Instant.now());

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(current));
        when(verificationAtomicUpdates.applyDecision(
                eq(PROVIDER_ID),
                eq(ids),
                eq(ProviderVerificationStatus.APPROVED),
                eq(ADMIN_ID),
                isNull(),
                any(Instant.class)
        )).thenReturn(Optional.of(approved));
        when(verificationAtomicUpdates.updateSubmittedDocumentStatuses(
                eq(PROVIDER_ID),
                eq(ids),
                eq(VerificationDocumentStatus.APPROVED),
                any(Instant.class)
        )).thenReturn(2L);

        var response = service.approveVerification(PROVIDER_ID);

        assertEquals(ProviderVerificationStatus.APPROVED, response.getStatus());
        verify(verificationAtomicUpdates).updateSubmittedDocumentStatuses(
                eq(PROVIDER_ID),
                eq(ids),
                eq(VerificationDocumentStatus.APPROVED),
                any(Instant.class)
        );
    }

    @Test
    void rejectVerification_updatesFrozenDocumentsAndReason() {
        List<String> ids = List.of(DOCUMENT_ID);
        ProviderVerification current = verification(
                ProviderVerificationStatus.UNDER_REVIEW, ids
        );
        ProviderVerification rejected = verification(
                ProviderVerificationStatus.REJECTED, ids
        );
        rejected.setDecisionReason("Invalid identification document");

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(current));
        when(verificationAtomicUpdates.applyDecision(
                eq(PROVIDER_ID),
                eq(ids),
                eq(ProviderVerificationStatus.REJECTED),
                eq(ADMIN_ID),
                eq("Invalid identification document"),
                any(Instant.class)
        )).thenReturn(Optional.of(rejected));
        when(verificationAtomicUpdates.updateSubmittedDocumentStatuses(
                eq(PROVIDER_ID),
                eq(ids),
                eq(VerificationDocumentStatus.REJECTED),
                any(Instant.class)
        )).thenReturn(1L);

        var response = service.rejectVerification(
                PROVIDER_ID, " Invalid identification document "
        );

        assertEquals(ProviderVerificationStatus.REJECTED, response.getStatus());
        verify(verificationAtomicUpdates).updateSubmittedDocumentStatuses(
                eq(PROVIDER_ID),
                eq(ids),
                eq(VerificationDocumentStatus.REJECTED),
                any(Instant.class)
        );
    }

    @Test
    void requestResubmission_doesNotChangeCurrentDocumentStatuses() {
        List<String> ids = List.of(DOCUMENT_ID);
        ProviderVerification current = verification(
                ProviderVerificationStatus.UNDER_REVIEW, ids
        );
        ProviderVerification requested = verification(
                ProviderVerificationStatus.RESUBMISSION_REQUESTED, ids
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(current));
        when(verificationAtomicUpdates.applyDecision(
                eq(PROVIDER_ID),
                eq(ids),
                eq(ProviderVerificationStatus.RESUBMISSION_REQUESTED),
                eq(ADMIN_ID),
                eq("Please upload a clearer identification document"),
                any(Instant.class)
        )).thenReturn(Optional.of(requested));

        var response = service.requestResubmission(
                PROVIDER_ID,
                "Please upload a clearer identification document"
        );

        assertEquals(
                ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                response.getStatus()
        );
        verify(verificationAtomicUpdates, never())
                .updateSubmittedDocumentStatuses(any(), any(), any(), any());
    }

    @Test
    void rejectVerification_requiresReason() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.rejectVerification(PROVIDER_ID, " ")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(providerVerificationRepository, verificationAtomicUpdates);
    }

    @Test
    void approveVerification_rejectsAlreadyApprovedVerification() {
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification(
                        ProviderVerificationStatus.APPROVED,
                        List.of(DOCUMENT_ID)
                )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.approveVerification(PROVIDER_ID)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verifyNoInteractions(verificationAtomicUpdates);
    }

    @Test
    void approveVerification_rejectsMissingSubmittedDocumentSnapshot() {
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification(
                        ProviderVerificationStatus.UNDER_REVIEW,
                        List.of()
                )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.approveVerification(PROVIDER_ID)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verifyNoInteractions(verificationAtomicUpdates);
    }

    private void setAdmin(String userId) {
        UserPrincipal principal = new UserPrincipal(
                userId,
                "admin@example.com",
                Role.ADMIN
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                )
        );
    }

    private ProviderVerification verification(
            ProviderVerificationStatus status,
            List<String> submittedIds
    ) {
        ProviderVerification verification = new ProviderVerification();
        verification.setId("verification-123");
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(status);
        verification.setSubmittedAt(Instant.now());
        verification.setCreatedAt(Instant.now());
        verification.setUpdatedAt(Instant.now());
        verification.setSubmittedDocumentIds(submittedIds);
        verification.setCoordinationRevision(1L);
        verification.setInFlightUploadCount(0);
        return verification;
    }

    private ProviderProfile profile() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId("user-123");
        profile.setDisplayName("Test Provider");
        profile.setBio("Test provider profile");
        profile.setDistrict("Colombo");
        profile.setCity("Maharagama");
        profile.setExperienceYears(3);
        return profile;
    }

    private VerificationDocument document(String id) {
        VerificationDocument document = new VerificationDocument();
        document.setId(id);
        document.setProviderId(PROVIDER_ID);
        document.setDocumentType(VerificationDocumentType.ID);
        document.setStorageKey("private-public-id-" + id);
        document.setAssetId("asset-" + id);
        document.setResourceType("image");
        document.setDeliveryType("authenticated");
        document.setOriginalFileName("ID Document.pdf");
        document.setFormat("pdf");
        document.setBytes(76304L);
        document.setStatus(VerificationDocumentStatus.SUBMITTED);
        document.setUploadedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
    }
}
