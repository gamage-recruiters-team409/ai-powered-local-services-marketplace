package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
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

    private static final String EMAIL = "provider@test.com";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    private ProviderVerificationServiceImpl providerVerificationService;

    @BeforeEach
    void setUp() {

        providerVerificationService =
                new ProviderVerificationServiceImpl(
                        providerProfileRepository,
                        verificationDocumentRepository,
                        providerVerificationRepository
                );

        UserPrincipal principal =
                new UserPrincipal(
                        USER_ID,
                        EMAIL,
                        Role.PROVIDER
                );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitMyVerification_shouldCreateSubmittedRecord() {

        stubCurrentProvider();

        stubDocuments(
                Instant.parse("2026-11-14T08:00:00Z")
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.empty());

        when(providerVerificationRepository.save(any(ProviderVerification.class)))
                .thenAnswer(invocation -> {

                    ProviderVerification saved =
                            invocation.getArgument(0);

                    saved.setId("verification-1");

                    return saved;
                });

        var response =
                providerVerificationService.submitMyVerification();

        assertEquals(
                "verification-1",
                response.getId()
        );

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                response.getStatus()
        );

        ArgumentCaptor<ProviderVerification> captor =
                ArgumentCaptor.forClass(ProviderVerification.class);

        verify(providerVerificationRepository)
                .save(captor.capture());

        assertEquals(
                PROVIDER_ID,
                captor.getValue().getProviderId()
        );

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                captor.getValue().getStatus()
        );
    }

    @Test
    void getMyVerificationStatus_shouldReturnNotSubmittedWhenNoRecordExists() {

        stubCurrentProvider();

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.empty());

        var response =
                providerVerificationService.getMyVerificationStatus();

        assertEquals(
                ProviderVerificationStatus.NOT_SUBMITTED,
                response.getStatus()
        );

        assertNull(response.getSubmittedAt());

        verify(providerVerificationRepository, never())
                .save(any());
    }

    @Test
    void submitMyVerification_shouldRejectWhenNoDocumentsExist() {

        stubCurrentProvider();

        when(
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID)
        ).thenReturn(List.of());

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> providerVerificationService.submitMyVerification()
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verify(providerVerificationRepository, never())
                .save(any());
    }

    @Test
    void submitMyVerification_shouldNotCreateDuplicateSubmission() {

        stubCurrentProvider();

        stubDocuments(
                Instant.parse("2026-11-14T08:00:00Z")
        );

        ProviderVerification existing =
                createVerification(
                        ProviderVerificationStatus.SUBMITTED,
                        null,
                        Instant.parse("2026-11-14T08:00:00Z")
                );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        var response =
                providerVerificationService.submitMyVerification();

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                response.getStatus()
        );

        verify(providerVerificationRepository, never())
                .save(any());
    }

    @Test
    void submitMyVerification_shouldAllowResubmissionAfterNewDocumentUpload() {

        stubCurrentProvider();

        Instant reviewedAt =
                Instant.parse("2026-11-13T10:00:00Z");

        stubDocuments(
                reviewedAt.plusSeconds(60)
        );

        ProviderVerification existing =
                createVerification(
                        ProviderVerificationStatus.REJECTED,
                        reviewedAt,
                        reviewedAt
                );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        when(providerVerificationRepository.save(any(ProviderVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response =
                providerVerificationService.submitMyVerification();

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                response.getStatus()
        );

        ArgumentCaptor<ProviderVerification> captor =
                ArgumentCaptor.forClass(ProviderVerification.class);

        verify(providerVerificationRepository)
                .save(captor.capture());

        ProviderVerification saved = captor.getValue();

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                saved.getStatus()
        );

        assertNull(saved.getReviewedAt());

        assertNull(saved.getReviewedByUserId());

        assertNull(saved.getDecisionReason());
    }

    @Test
    void submitMyVerification_shouldRejectResubmissionWithoutUpdatedDocuments() {

        stubCurrentProvider();

        Instant reviewedAt =
                Instant.parse("2026-11-13T10:00:00Z");

        stubDocuments(
                reviewedAt.minusSeconds(60)
        );

        ProviderVerification existing =
                createVerification(
                        ProviderVerificationStatus.REJECTED,
                        reviewedAt,
                        reviewedAt
                );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> providerVerificationService.submitMyVerification()
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verify(providerVerificationRepository, never())
                .save(any());
    }

    @Test
    void submitMyVerification_shouldRejectCaseAlreadyUnderReview() {

        stubCurrentProvider();

        stubDocuments(
                Instant.parse("2026-11-14T08:00:00Z")
        );

        ProviderVerification existing =
                createVerification(
                        ProviderVerificationStatus.UNDER_REVIEW,
                        Instant.parse("2026-11-13T10:00:00Z"),
                        Instant.parse("2026-11-13T10:00:00Z")
                );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> providerVerificationService.submitMyVerification()
                );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        verify(providerVerificationRepository, never())
                .save(any());
    }

    @Test
    void submitMyVerification_shouldRejectAlreadyApprovedCase() {

        stubCurrentProvider();

        stubDocuments(
                Instant.parse("2026-11-14T08:00:00Z")
        );

        ProviderVerification existing =
                createVerification(
                        ProviderVerificationStatus.APPROVED,
                        Instant.parse("2026-11-13T10:00:00Z"),
                        Instant.parse("2026-11-13T10:00:00Z")
                );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> providerVerificationService.submitMyVerification()
                );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        verify(providerVerificationRepository, never())
                .save(any());
    }

    private void stubCurrentProvider() {

        ProviderProfile profile =
                new ProviderProfile();

        profile.setId(PROVIDER_ID);
        profile.setUserId(USER_ID);
        profile.setDisplayName("Test Provider");

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
    }

    private void stubDocuments(Instant uploadedAt) {

        VerificationDocument document =
                new VerificationDocument();

        document.setId("document-1");
        document.setProviderId(PROVIDER_ID);
        document.setUploadedAt(uploadedAt);

        when(
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID)
        ).thenReturn(List.of(document));
    }

    private ProviderVerification createVerification(
            ProviderVerificationStatus status,
            Instant reviewedAt,
            Instant updatedAt
    ) {

        ProviderVerification verification =
                new ProviderVerification();

        verification.setId("verification-1");
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(status);
        verification.setSubmittedAt(
                Instant.parse("2026-11-12T08:00:00Z")
        );
        verification.setReviewedAt(reviewedAt);
        verification.setReviewedByUserId("admin-user-1");
        verification.setDecisionReason("Please review the submitted information");
        verification.setUpdatedAt(updatedAt);

        return verification;
    }
}