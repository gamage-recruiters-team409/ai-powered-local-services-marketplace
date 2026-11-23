
package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;

import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationExpiredResubmissionTest {

    private static final String USER_ID = "provider-user-1";

    private static final String PROVIDER_ID = "provider-profile-1";

    private static final Instant EXPIRED_AT =
            Instant.parse("2026-11-01T00:00:00Z");

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

        ProviderProfile profile = new ProviderProfile();

        profile.setId(PROVIDER_ID);

        profile.setUserId(USER_ID);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReplaceExpiredDocumentAndPreserveValidDocuments() {

        ProviderVerification expired =
                createExpiredVerification();

        ProviderVerification submitted =
                createExpiredVerification();

        submitted.setStatus(
                ProviderVerificationStatus.SUBMITTED
        );

        submitted.setSubmittedDocumentIds(
                List.of(
                        "valid-certificate",
                        "new-id"
                )
        );

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID),
                any(Instant.class)
        )).thenReturn(expired);

        VerificationDocument oldId =
                createDocument(
                        "old-id",
                        VerificationDocumentType.ID,
                        LocalDate.of(2020, 1, 1),
                        EXPIRED_AT.minusSeconds(100)
                );

        VerificationDocument validCertificate =
                createDocument(
                        "valid-certificate",
                        VerificationDocumentType.CERTIFICATE,
                        LocalDate.of(2030, 12, 31),
                        EXPIRED_AT.minusSeconds(200)
                );

        VerificationDocument newId =
                createDocument(
                        "new-id",
                        VerificationDocumentType.ID,
                        LocalDate.of(2030, 12, 31),
                        EXPIRED_AT.plusSeconds(100)
                );

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(
                        newId,
                        validCertificate,
                        oldId
                ));

        when(verificationAtomicUpdates.submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(7L),
                eq(List.of(
                        "valid-certificate",
                        "new-id"
                )),
                any(Instant.class)
        )).thenReturn(Optional.of(submitted));

        var response = service.submitMyVerification();

        assertEquals(
                ProviderVerificationStatus.SUBMITTED,
                response.getStatus()
        );

        verify(verificationAtomicUpdates).submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(7L),
                eq(List.of(
                        "valid-certificate",
                        "new-id"
                )),
                any(Instant.class)
        );
    }

    @Test
    void shouldRejectResubmissionWhenExpiredDocumentTypeIsNotReplaced() {

        ProviderVerification expired =
                createExpiredVerification();

        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID),
                any(Instant.class)
        )).thenReturn(expired);

        VerificationDocument oldId =
                createDocument(
                        "old-id",
                        VerificationDocumentType.ID,
                        LocalDate.of(2020, 1, 1),
                        EXPIRED_AT.minusSeconds(100)
                );

        VerificationDocument validCertificate =
                createDocument(
                        "valid-certificate",
                        VerificationDocumentType.CERTIFICATE,
                        LocalDate.of(2030, 12, 31),
                        EXPIRED_AT.minusSeconds(200)
                );

        VerificationDocument newCertificate =
                createDocument(
                        "new-certificate",
                        VerificationDocumentType.CERTIFICATE,
                        LocalDate.of(2030, 12, 31),
                        EXPIRED_AT.plusSeconds(100)
                );

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(
                        newCertificate,
                        validCertificate,
                        oldId
                ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verify(verificationAtomicUpdates, never()).submitIfUnchanged(
                any(),
                anyLong(),
                any(),
                any()
        );
    }

    private ProviderVerification createExpiredVerification() {

        ProviderVerification verification =
                new ProviderVerification();

        verification.setId("verification-1");

        verification.setProviderId(PROVIDER_ID);

        verification.setStatus(
                ProviderVerificationStatus.EXPIRED
        );

        verification.setExpiredAt(EXPIRED_AT);

        verification.setUpdatedAt(EXPIRED_AT);

        verification.setGracePeriodEndsAt(
                EXPIRED_AT.plusSeconds(864000)
        );

        verification.setExpiredDocumentIds(
                List.of("old-id")
        );

        verification.setSubmittedDocumentIds(
                List.of(
                        "old-id",
                        "valid-certificate"
                )
        );

        verification.setCoordinationRevision(7L);

        verification.setInFlightUploadCount(0);

        return verification;
    }

    private VerificationDocument createDocument(
            String id,
            VerificationDocumentType type,
            LocalDate expiryDate,
            Instant uploadedAt
    ) {

        VerificationDocument document =
                new VerificationDocument();

        document.setId(id);

        document.setProviderId(PROVIDER_ID);

        document.setDocumentType(type);

        document.setExpiryDate(expiryDate);

        document.setUploadedAt(uploadedAt);

        return document;
    }
}
