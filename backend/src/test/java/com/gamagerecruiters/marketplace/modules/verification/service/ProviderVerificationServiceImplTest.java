package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationServiceImplTest {

    private static final String USER_ID = "provider-user-1";
    private static final String PROVIDER_ID = "provider-profile-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    @Mock
    private VerificationAtomicUpdates verificationAtomicUpdates;

    private ProviderVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProviderVerificationServiceImpl(
                providerProfileRepository,
                verificationDocumentRepository,
                providerVerificationRepository,
                verificationAtomicUpdates
        );

        UserPrincipal principal = new UserPrincipal(
                USER_ID,
                "provider@test.com",
                Role.PROVIDER
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitMyVerification_freezesDocumentIdsAndSubmits() {
        stubCurrentProvider();

        ProviderVerification current = verification(
                ProviderVerificationStatus.NOT_SUBMITTED,
                null,
                2L
        );
        ProviderVerification submitted = verification(
                ProviderVerificationStatus.SUBMITTED,
                null,
                3L
        );
        submitted.setId("verification-1");
        submitted.setSubmittedAt(Instant.now());
        submitted.setSubmittedDocumentIds(List.of("document-1"));

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(current);

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document("document-1", Instant.now())));

        when(verificationAtomicUpdates.submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(2L),
                eq(List.of("document-1")),
                any(Instant.class)
        )).thenReturn(Optional.of(submitted));

        var response = service.submitMyVerification();

        assertEquals("verification-1", response.getId());
        assertEquals(ProviderVerificationStatus.SUBMITTED, response.getStatus());

        verify(verificationAtomicUpdates).submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(2L),
                eq(List.of("document-1")),
                any(Instant.class)
        );
    }

    @Test
    void getMyVerificationStatus_returnsNotSubmittedWhenNoRecordExists() {
        stubCurrentProvider();

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.empty());

        var response = service.getMyVerificationStatus();

        assertEquals(ProviderVerificationStatus.NOT_SUBMITTED, response.getStatus());
        assertNull(response.getSubmittedAt());
        verify(providerVerificationRepository, never()).save(any());
    }

    @Test
    void submitMyVerification_rejectsWhenNoDocumentsExist() {
        stubCurrentProvider();

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(verification(
                ProviderVerificationStatus.NOT_SUBMITTED, null, 0L
        ));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(verificationAtomicUpdates, never())
                .submitIfUnchanged(any(), anyLong(), any(), any());
    }

    @Test
    void submitMyVerification_doesNotCreateDuplicateSubmission() {
        stubCurrentProvider();

        ProviderVerification existing = verification(
                ProviderVerificationStatus.SUBMITTED,
                null,
                4L
        );

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(existing);

        var response = service.submitMyVerification();

        assertEquals(ProviderVerificationStatus.SUBMITTED, response.getStatus());
        verify(verificationDocumentRepository, never())
                .findAllByProviderIdOrderByUploadedAtDesc(any());
        verify(verificationAtomicUpdates, never())
                .submitIfUnchanged(any(), anyLong(), any(), any());
    }

    @Test
    void submitMyVerification_allowsResubmissionAfterUpdatedDocument() {
        stubCurrentProvider();

        Instant reviewedAt = Instant.parse("2026-11-13T10:00:00Z");
        ProviderVerification rejected = verification(
                ProviderVerificationStatus.REJECTED,
                reviewedAt,
                5L
        );
        ProviderVerification submitted = verification(
                ProviderVerificationStatus.SUBMITTED,
                null,
                6L
        );
        submitted.setSubmittedDocumentIds(List.of("document-new"));

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(rejected);

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document(
                        "document-new",
                        reviewedAt.plusSeconds(60)
                )));

        when(verificationAtomicUpdates.submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(5L),
                eq(List.of("document-new")),
                any(Instant.class)
        )).thenReturn(Optional.of(submitted));

        var response = service.submitMyVerification();

        assertEquals(ProviderVerificationStatus.SUBMITTED, response.getStatus());
        verify(verificationAtomicUpdates).submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(5L),
                eq(List.of("document-new")),
                any(Instant.class)
        );
    }

    @Test
    void submitMyVerification_rejectsResubmissionWithoutUpdatedDocuments() {
        stubCurrentProvider();

        Instant reviewedAt = Instant.parse("2026-11-13T10:00:00Z");
        ProviderVerification rejected = verification(
                ProviderVerificationStatus.REJECTED,
                reviewedAt,
                5L
        );

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(rejected);

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document(
                        "old-document",
                        reviewedAt.minusSeconds(60)
                )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(verificationAtomicUpdates, never())
                .submitIfUnchanged(any(), anyLong(), any(), any());
    }

    @Test
    void submitMyVerification_rejectsCaseAlreadyUnderReview() {
        stubCurrentProvider();

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(verification(
                ProviderVerificationStatus.UNDER_REVIEW, Instant.now(), 1L
        ));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document(
                        "document-1",
                        Instant.now()
                )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(verificationAtomicUpdates, never())
                .submitIfUnchanged(any(), anyLong(), any(), any());
    }

    @Test
    void submitMyVerification_rejectsAlreadyApprovedCase() {
        stubCurrentProvider();

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(verification(
                ProviderVerificationStatus.APPROVED, Instant.now(), 1L
        ));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document(
                        "document-1",
                        Instant.now()
                )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(verificationAtomicUpdates, never())
                .submitIfUnchanged(any(), anyLong(), any(), any());
    }

    private void stubCurrentProvider() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId(USER_ID);
        profile.setDisplayName("Test Provider");

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
    }

    private ProviderVerification verification(
            ProviderVerificationStatus status,
            Instant reviewedAt,
            long revision
    ) {
        ProviderVerification verification = new ProviderVerification();
        verification.setId("verification-1");
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(status);
        verification.setSubmittedAt(Instant.parse("2026-11-12T08:00:00Z"));
        verification.setReviewedAt(reviewedAt);
        verification.setUpdatedAt(Instant.parse("2026-11-13T10:00:00Z"));
        verification.setCoordinationRevision(revision);
        verification.setInFlightUploadCount(0);
        return verification;
    }

    private VerificationDocument document(String id, Instant uploadedAt) {
        VerificationDocument document = new VerificationDocument();
        document.setId(id);
        document.setProviderId(PROVIDER_ID);
        document.setUploadedAt(uploadedAt);
        return document;
    }
}
