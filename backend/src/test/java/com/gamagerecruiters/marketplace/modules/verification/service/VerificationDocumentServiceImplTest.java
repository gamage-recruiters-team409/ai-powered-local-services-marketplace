package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.StoredVerificationFile;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class VerificationDocumentServiceImplTest {

    private static final String USER_ID = "provider-user-1";
    private static final String PROVIDER_ID = "provider-profile-1";
    private static final String RESERVATION_ID = "reservation-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private VerificationAtomicUpdates verificationAtomicUpdates;

    @Mock
    private VerificationFileStorageService verificationFileStorageService;

    private VerificationDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VerificationDocumentServiceImpl(
                providerProfileRepository,
                verificationDocumentRepository,
                verificationAtomicUpdates,
                verificationFileStorageService
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
        service.shutdownLeaseHeartbeatExecutor();
    }

    @Test
    void uploadMyDocument_reservesUploadsAndPersistsDocument() {
        stubCurrentProvider();
        stubReservationAndRenewal();

        MockMultipartFile file = testFile();
        when(verificationFileStorageService.upload(PROVIDER_ID, file))
                .thenReturn(storedFile());

        when(verificationDocumentRepository.save(any(VerificationDocument.class)))
                .thenAnswer(invocation -> {
                    VerificationDocument document = invocation.getArgument(0);
                    document.setId("document-1");
                    return document;
                });

        VerificationDocumentResponse response = service.uploadMyDocument(
                VerificationDocumentType.ID,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2031, 1, 1),
                file
        );

        assertEquals("document-1", response.getId());
        assertEquals(VerificationDocumentType.ID, response.getDocumentType());
        assertEquals(VerificationDocumentStatus.SUBMITTED, response.getStatus());
        assertEquals("nic.pdf", response.getOriginalFileName());

        verify(verificationAtomicUpdates).reserveDocumentUpload(
                eq(PROVIDER_ID), any(Instant.class)
        );
        verify(verificationAtomicUpdates).renewDocumentUploadReservation(
                eq(PROVIDER_ID), eq(RESERVATION_ID), any(Instant.class)
        );
        verify(verificationAtomicUpdates).releaseDocumentUpload(
                PROVIDER_ID,
                RESERVATION_ID
        );
    }

    @Test
    void uploadMyDocument_doesNotCallCloudinaryWhenReservationIsRejected() {
        stubCurrentProvider();

        MockMultipartFile file = testFile();
        doThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Verification is not accepting uploads"
        )).when(verificationAtomicUpdates).reserveDocumentUpload(
                eq(PROVIDER_ID), any(Instant.class)
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.uploadMyDocument(
                        VerificationDocumentType.ID, null, null, file
                )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(verificationFileStorageService, never()).upload(any(), any());
        verify(verificationDocumentRepository, never()).save(any());
        verify(verificationAtomicUpdates, never())
                .releaseDocumentUpload(any(), any());
    }

    @Test
    void uploadMyDocument_deletesCloudinaryFileWhenDatabaseSaveFails() {
        stubCurrentProvider();
        stubReservationAndRenewal();

        MockMultipartFile file = testFile();
        when(verificationFileStorageService.upload(PROVIDER_ID, file))
                .thenReturn(storedFile());

        when(verificationDocumentRepository.save(any(VerificationDocument.class)))
                .thenThrow(new RuntimeException("Database failure"));

        assertThrows(
                RuntimeException.class,
                () -> service.uploadMyDocument(
                        VerificationDocumentType.ID, null, null, file
                )
        );

        verify(verificationFileStorageService).delete("private-public-id-1");
        verify(verificationAtomicUpdates).releaseDocumentUpload(
                PROVIDER_ID,
                RESERVATION_ID
        );
    }

    @Test
    void uploadMyDocument_releasesReservationWhenCloudinaryUploadFails() {
        stubCurrentProvider();
        stubReservationOnly();

        MockMultipartFile file = testFile();
        when(verificationFileStorageService.upload(PROVIDER_ID, file))
                .thenThrow(new RuntimeException("Cloudinary failure"));

        assertThrows(
                RuntimeException.class,
                () -> service.uploadMyDocument(
                        VerificationDocumentType.ID, null, null, file
                )
        );

        verify(verificationAtomicUpdates).releaseDocumentUpload(
                PROVIDER_ID,
                RESERVATION_ID
        );
        verify(verificationDocumentRepository, never()).save(any());
    }

    @Test
    void uploadMyDocument_deletesCloudinaryFileWhenReservationWasRecovered() {
        stubCurrentProvider();
        stubReservationOnly();

        MockMultipartFile file = testFile();
        when(verificationFileStorageService.upload(PROVIDER_ID, file))
                .thenReturn(storedFile());

        when(verificationAtomicUpdates.renewDocumentUploadReservation(
                eq(PROVIDER_ID),
                eq(RESERVATION_ID),
                any(Instant.class)
        )).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.uploadMyDocument(
                        VerificationDocumentType.ID, null, null, file
                )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(verificationFileStorageService).delete("private-public-id-1");
        verify(verificationDocumentRepository, never()).save(any());
        verify(verificationAtomicUpdates).releaseDocumentUpload(
                PROVIDER_ID,
                RESERVATION_ID
        );
    }

    @Test
    void uploadMyDocument_rejectsInvalidDateRangeBeforeReserving() {
        stubCurrentProvider();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.uploadMyDocument(
                        VerificationDocumentType.CERTIFICATE,
                        LocalDate.of(2026, 5, 10),
                        LocalDate.of(2026, 5, 1),
                        testFile()
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(verificationAtomicUpdates, never())
                .reserveDocumentUpload(any(), any());
        verify(verificationFileStorageService, never()).upload(any(), any());
    }

    @Test
    void uploadMyDocument_failsWhenProviderProfileDoesNotExist() {
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.uploadMyDocument(
                        VerificationDocumentType.ID, null, null, testFile()
                )
        );

        verify(verificationAtomicUpdates, never())
                .reserveDocumentUpload(any(), any());
        verify(verificationFileStorageService, never()).upload(any(), any());
    }

    @Test
    void getMyDocuments_returnsCurrentProvidersDocuments() {
        stubCurrentProvider();

        VerificationDocument document = new VerificationDocument();
        document.setId("document-1");
        document.setProviderId(PROVIDER_ID);
        document.setDocumentType(VerificationDocumentType.BUSINESS);
        document.setOriginalFileName("business-registration.pdf");
        document.setFormat("pdf");
        document.setBytes(2000L);
        document.setStatus(VerificationDocumentStatus.SUBMITTED);
        document.setUploadedAt(Instant.now());

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(document));

        List<VerificationDocumentResponse> response = service.getMyDocuments();

        assertEquals(1, response.size());
        assertEquals("document-1", response.get(0).getId());
        assertEquals(VerificationDocumentType.BUSINESS, response.get(0).getDocumentType());
    }

    private void stubReservationAndRenewal() {
        stubReservationOnly();

        when(verificationAtomicUpdates.renewDocumentUploadReservation(
                eq(PROVIDER_ID),
                eq(RESERVATION_ID),
                any(Instant.class)
        )).thenReturn(true);
    }

    private void stubReservationOnly() {
        when(verificationAtomicUpdates.reserveDocumentUpload(
                eq(PROVIDER_ID),
                any(Instant.class)
        )).thenReturn(RESERVATION_ID);
    }

    private void stubCurrentProvider() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId(USER_ID);
        profile.setDisplayName("Test Provider");

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
    }

    private MockMultipartFile testFile() {
        return new MockMultipartFile(
                "file",
                "nic.pdf",
                "application/pdf",
                "test-file-content".getBytes()
        );
    }

    private StoredVerificationFile storedFile() {
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
