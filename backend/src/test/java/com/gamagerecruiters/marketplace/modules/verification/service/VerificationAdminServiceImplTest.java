
package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
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

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationDetailResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationQueueItemResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class VerificationAdminServiceImplTest {

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @InjectMocks
    private VerificationAdminServiceImpl verificationAdminService;

    private static final String PROVIDER_ID = "provider-123";
    private static final String ADMIN_ID = "admin-123";

    @BeforeEach
    void setUp() {

        UserPrincipal principal = new UserPrincipal(
                ADMIN_ID,
                "admin@example.com",
                Role.ADMIN
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
    void getPendingVerifications_returnsQueueItems() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.SUBMITTED
        );

        when(providerVerificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(anyCollection()))
                .thenReturn(List.of(verification));

        when(providerProfileRepository.findById(PROVIDER_ID))
                .thenReturn(Optional.of(createProfile()));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(
                        createDocument("doc-1"),
                        createDocument("doc-2")
                ));

        List<AdminVerificationQueueItemResponse> result =
                verificationAdminService.getPendingVerifications();

        assertEquals(1, result.size());
        assertEquals(PROVIDER_ID, result.get(0).providerId());
        assertEquals("Test Provider", result.get(0).displayName());
        assertEquals(2, result.get(0).documentCount());

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                result.get(0).status()
        );

        verify(providerVerificationRepository)
                .findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(
                                ProviderVerificationStatus.SUBMITTED,
                                ProviderVerificationStatus.UNDER_REVIEW
                        )
                );
    }

    @Test
    void getVerificationDetails_returnsProviderAndDocuments() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.SUBMITTED
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        when(providerProfileRepository.findById(PROVIDER_ID))
                .thenReturn(Optional.of(createProfile()));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(createDocument("doc-1")));

        AdminVerificationDetailResponse result =
                verificationAdminService.getVerificationDetails(PROVIDER_ID);

        assertEquals(PROVIDER_ID, result.providerId());
        assertEquals("Test Provider", result.displayName());

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                result.status()
        );

        assertEquals(1, result.documents().size());

        assertEquals(
                "ID Document.pdf",
                result.documents().get(0).getOriginalFileName()
        );
    }

    @Test
    void startReview_changesStatusToUnderReview() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.SUBMITTED
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        when(providerVerificationRepository.save(any(ProviderVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderVerificationResponse result =
                verificationAdminService.startReview(PROVIDER_ID);

        assertEquals(
                ProviderVerificationStatus.UNDER_REVIEW,
                result.getStatus()
        );

        assertEquals(
                ProviderVerificationStatus.UNDER_REVIEW,
                verification.getStatus()
        );

        assertNotNull(verification.getUpdatedAt());

        verify(providerVerificationRepository).save(verification);
    }

    @Test
    void startReview_doesNotSaveAgainWhenAlreadyUnderReview() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.UNDER_REVIEW
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        ProviderVerificationResponse result =
                verificationAdminService.startReview(PROVIDER_ID);

        assertEquals(
                ProviderVerificationStatus.UNDER_REVIEW,
                result.getStatus()
        );

        verify(providerVerificationRepository, never())
                .save(any(ProviderVerification.class));
    }

    @Test
    void approveVerification_approvesVerificationAndDocuments() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.UNDER_REVIEW
        );

        List<VerificationDocument> documents = new ArrayList<>(
                List.of(
                        createDocument("doc-1"),
                        createDocument("doc-2")
                )
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        when(providerVerificationRepository.save(any(ProviderVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(documents);

        ProviderVerificationResponse result =
                verificationAdminService.approveVerification(PROVIDER_ID);

        assertEquals(
                ProviderVerificationStatus.APPROVED,
                result.getStatus()
        );

        assertEquals(ADMIN_ID, verification.getReviewedByUserId());
        assertNotNull(verification.getReviewedAt());

        for (VerificationDocument document : documents) {
            assertEquals(
                    VerificationDocumentStatus.APPROVED,
                    document.getStatus()
            );
        }

        verify(verificationDocumentRepository).saveAll(documents);
    }

    @Test
    void rejectVerification_rejectsVerificationAndDocuments() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.UNDER_REVIEW
        );

        List<VerificationDocument> documents = new ArrayList<>(
                List.of(createDocument("doc-1"))
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        when(providerVerificationRepository.save(any(ProviderVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(documents);

        ProviderVerificationResponse result =
                verificationAdminService.rejectVerification(
                        PROVIDER_ID,
                        "Invalid identification document"
                );

        assertEquals(
                ProviderVerificationStatus.REJECTED,
                result.getStatus()
        );

        assertEquals(
                "Invalid identification document",
                verification.getDecisionReason()
        );

        assertEquals(ADMIN_ID, verification.getReviewedByUserId());

        assertEquals(
                VerificationDocumentStatus.REJECTED,
                documents.get(0).getStatus()
        );

        verify(verificationDocumentRepository).saveAll(documents);
    }

    @Test
    void requestResubmission_updatesVerificationWithoutChangingDocuments() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.UNDER_REVIEW
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        when(providerVerificationRepository.save(any(ProviderVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderVerificationResponse result =
                verificationAdminService.requestResubmission(
                        PROVIDER_ID,
                        "Please upload a clearer identification document"
                );

        assertEquals(
                ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                result.getStatus()
        );

        assertEquals(
                "Please upload a clearer identification document",
                verification.getDecisionReason()
        );

        assertEquals(ADMIN_ID, verification.getReviewedByUserId());

        verifyNoInteractions(verificationDocumentRepository);
    }

    @Test
    void rejectVerification_throwsExceptionWhenReasonIsBlank() {

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> verificationAdminService.rejectVerification(
                        PROVIDER_ID,
                        " "
                )
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verifyNoInteractions(providerVerificationRepository);
    }

    @Test
    void approveVerification_rejectsAlreadyApprovedVerification() {

        ProviderVerification verification = createVerification(
                ProviderVerificationStatus.APPROVED
        );

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> verificationAdminService.approveVerification(PROVIDER_ID)
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        verify(providerVerificationRepository, never())
                .save(any(ProviderVerification.class));
    }

    private ProviderVerification createVerification(
            ProviderVerificationStatus status
    ) {

        ProviderVerification verification = new ProviderVerification();

        verification.setId("verification-123");
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(status);
        verification.setSubmittedAt(Instant.now());
        verification.setCreatedAt(Instant.now());
        verification.setUpdatedAt(Instant.now());

        return verification;
    }

    private ProviderProfile createProfile() {

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

    private VerificationDocument createDocument(String id) {

        VerificationDocument document = new VerificationDocument();

        document.setId(id);
        document.setProviderId(PROVIDER_ID);
        document.setDocumentType(VerificationDocumentType.ID);
        document.setOriginalFileName("ID Document.pdf");
        document.setFormat("pdf");
        document.setBytes(76304L);
        document.setStatus(VerificationDocumentStatus.SUBMITTED);
        document.setUploadedAt(Instant.now());
        document.setUpdatedAt(Instant.now());

        return document;
    }
}
