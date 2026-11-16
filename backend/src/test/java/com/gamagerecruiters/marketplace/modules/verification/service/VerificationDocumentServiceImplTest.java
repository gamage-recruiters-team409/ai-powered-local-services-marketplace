
package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.mock.web.MockMultipartFile;
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
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.StoredVerificationFile;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class VerificationDocumentServiceImplTest {

    private static final String USER_ID = "provider-user-1";
    private static final String EMAIL = "provider@test.com";
    private static final String PROVIDER_ID = "provider-profile-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    @Mock
    private VerificationFileStorageService verificationFileStorageService;

    private VerificationDocumentServiceImpl verificationDocumentService;

    @BeforeEach
    void setUp() {

        verificationDocumentService =
                new VerificationDocumentServiceImpl(
                        providerProfileRepository,
                        verificationDocumentRepository,
                        providerVerificationRepository,
                        verificationFileStorageService
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

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadMyDocument_shouldUploadAndPersistDocument() {

        ProviderProfile providerProfile =
                createProviderProfile();

        MockMultipartFile file =
                createTestFile();

        StoredVerificationFile storedFile =
                createStoredFile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        // No existing verification record: first-time upload.
        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.empty());

        when(
                verificationFileStorageService.upload(
                        PROVIDER_ID,
                        file
                )
        ).thenReturn(storedFile);

        when(
                verificationDocumentRepository.save(
                        any(VerificationDocument.class)
                )
        ).thenAnswer(invocation -> {

            VerificationDocument document =
                    invocation.getArgument(0);

            document.setId("document-1");

            return document;
        });

        VerificationDocumentResponse response =
                verificationDocumentService.uploadMyDocument(
                        VerificationDocumentType.ID,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2031, 1, 1),
                        file
                );

        assertEquals("document-1", response.getId());

        assertEquals(
                VerificationDocumentType.ID,
                response.getDocumentType()
        );

        assertEquals(
                VerificationDocumentStatus.SUBMITTED,
                response.getStatus()
        );

        assertEquals(
                "nic.pdf",
                response.getOriginalFileName()
        );

        ArgumentCaptor<VerificationDocument> captor =
                ArgumentCaptor.forClass(
                        VerificationDocument.class
                );

        verify(verificationDocumentRepository)
                .save(captor.capture());

        VerificationDocument saved =
                captor.getValue();

        assertEquals(
                PROVIDER_ID,
                saved.getProviderId()
        );

        assertEquals(
                "private-public-id-1",
                saved.getStorageKey()
        );
    }

    @Test
    void uploadMyDocument_shouldAllowResubmissionAndExpiredStatuses() {

        ProviderProfile providerProfile =
                createProviderProfile();

        MockMultipartFile file =
                createTestFile();

        ProviderVerification verification =
                new ProviderVerification();

        verification.setProviderId(PROVIDER_ID);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        when(
                verificationFileStorageService.upload(
                        PROVIDER_ID,
                        file
                )
        ).thenReturn(createStoredFile());

        when(
                verificationDocumentRepository.save(
                        any(VerificationDocument.class)
                )
        ).thenAnswer(invocation -> {

            VerificationDocument document =
                    invocation.getArgument(0);

            document.setId("document-allowed");

            return document;
        });

        List<ProviderVerificationStatus> allowedStatuses =
                List.of(
                        ProviderVerificationStatus.NOT_SUBMITTED,
                        ProviderVerificationStatus.REJECTED,
                        ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                        ProviderVerificationStatus.EXPIRED
                );

        for (ProviderVerificationStatus status : allowedStatuses) {

            verification.setStatus(status);

            VerificationDocumentResponse response =
                    verificationDocumentService.uploadMyDocument(
                            VerificationDocumentType.ID,
                            null,
                            null,
                            file
                    );

            assertEquals(
                    "document-allowed",
                    response.getId()
            );
        }

        verify(verificationFileStorageService, times(4))
                .upload(PROVIDER_ID, file);
    }

    @Test
    void uploadMyDocument_shouldRejectUploadsForRestrictedStatuses() {

        ProviderProfile providerProfile =
                createProviderProfile();

        MockMultipartFile file =
                createTestFile();

        ProviderVerification verification =
                new ProviderVerification();

        verification.setProviderId(PROVIDER_ID);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        List<ProviderVerificationStatus> restrictedStatuses =
                List.of(
                        ProviderVerificationStatus.SUBMITTED,
                        ProviderVerificationStatus.UNDER_REVIEW,
                        ProviderVerificationStatus.APPROVED,
                        ProviderVerificationStatus.SUSPENDED
                );

        for (ProviderVerificationStatus status : restrictedStatuses) {

            verification.setStatus(status);

            ResponseStatusException exception =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    verificationDocumentService.uploadMyDocument(
                                            VerificationDocumentType.ID,
                                            null,
                                            null,
                                            file
                                    )
                    );

            assertEquals(
                    HttpStatus.CONFLICT,
                    exception.getStatusCode()
            );
        }

        // Cloudinary must never be called for restricted statuses.
        verify(
                verificationFileStorageService,
                never()
        ).upload(any(), any());

        verify(
                verificationDocumentRepository,
                never()
        ).save(any(VerificationDocument.class));
    }

    @Test
    void uploadMyDocument_shouldRejectWhenVerificationStatusIsNull() {

        ProviderProfile providerProfile =
                createProviderProfile();

        MockMultipartFile file =
                createTestFile();

        ProviderVerification verification =
                new ProviderVerification();

        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(null);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        when(providerVerificationRepository.findByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(verification));

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                verificationDocumentService.uploadMyDocument(
                                        VerificationDocumentType.ID,
                                        null,
                                        null,
                                        file
                                )
                );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        verify(
                verificationFileStorageService,
                never()
        ).upload(any(), any());
    }

    @Test
    void uploadMyDocument_shouldRejectInvalidDateRange() {

        ProviderProfile providerProfile =
                createProviderProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        MockMultipartFile file =
                createTestFile();

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                verificationDocumentService.uploadMyDocument(
                                        VerificationDocumentType.CERTIFICATE,
                                        LocalDate.of(2026, 5, 10),
                                        LocalDate.of(2026, 5, 1),
                                        file
                                )
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verify(
                verificationFileStorageService,
                never()
        ).upload(any(), any());
    }

    @Test
    void uploadMyDocument_shouldFailWhenProviderProfileDoesNotExist() {

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        MockMultipartFile file =
                createTestFile();

        assertThrows(
                ResourceNotFoundException.class,
                () ->
                        verificationDocumentService.uploadMyDocument(
                                VerificationDocumentType.ID,
                                null,
                                null,
                                file
                        )
        );

        verify(
                verificationFileStorageService,
                never()
        ).upload(any(), any());
    }

    @Test
    void uploadMyDocument_shouldDeleteStoredFileWhenDatabaseSaveFails() {

        ProviderProfile providerProfile =
                createProviderProfile();

        MockMultipartFile file =
                createTestFile();

        StoredVerificationFile storedFile =
                createStoredFile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        when(
                verificationFileStorageService.upload(
                        PROVIDER_ID,
                        file
                )
        ).thenReturn(storedFile);

        when(
                verificationDocumentRepository.save(
                        any(VerificationDocument.class)
                )
        ).thenThrow(
                new RuntimeException("Database failure")
        );

        assertThrows(
                RuntimeException.class,
                () ->
                        verificationDocumentService.uploadMyDocument(
                                VerificationDocumentType.ID,
                                null,
                                null,
                                file
                        )
        );

        verify(verificationFileStorageService)
                .delete("private-public-id-1");
    }

    @Test
    void getMyDocuments_shouldReturnCurrentProvidersDocuments() {

        ProviderProfile providerProfile =
                createProviderProfile();

        VerificationDocument document =
                new VerificationDocument();

        document.setId("document-1");
        document.setProviderId(PROVIDER_ID);

        document.setDocumentType(
                VerificationDocumentType.BUSINESS
        );

        document.setOriginalFileName(
                "business-registration.pdf"
        );

        document.setFormat("pdf");
        document.setBytes(2000L);

        document.setStatus(
                VerificationDocumentStatus.SUBMITTED
        );

        document.setUploadedAt(Instant.now());

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(providerProfile));

        when(
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(
                                PROVIDER_ID
                        )
        ).thenReturn(List.of(document));

        List<VerificationDocumentResponse> response =
                verificationDocumentService.getMyDocuments();

        assertEquals(1, response.size());

        assertEquals(
                "document-1",
                response.get(0).getId()
        );

        assertEquals(
                VerificationDocumentType.BUSINESS,
                response.get(0).getDocumentType()
        );
    }

    private ProviderProfile createProviderProfile() {

        ProviderProfile profile =
                new ProviderProfile();

        profile.setId(PROVIDER_ID);
        profile.setUserId(USER_ID);
        profile.setDisplayName("Hiba Services");

        return profile;
    }

    private MockMultipartFile createTestFile() {

        return new MockMultipartFile(
                "file",
                "nic.pdf",
                "application/pdf",
                "test-file-content".getBytes()
        );
    }

    private StoredVerificationFile createStoredFile() {

        return new StoredVerificationFile(
                "asset-1",
                "private-public-id-1",
                "image",
                "authenticated",
                "pdf",
                1200L,
                "nic.pdf"
        );
    }
}
