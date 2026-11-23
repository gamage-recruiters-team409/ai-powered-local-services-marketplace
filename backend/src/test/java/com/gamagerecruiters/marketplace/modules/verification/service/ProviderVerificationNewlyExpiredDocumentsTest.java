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
import java.time.ZoneId;
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

/**
 * Regression coverage for documents that expire BETWEEN the initial
 * verification expiry event and the provider's actual resubmission.
 * All dates are relative to today in the same time zone as the service.
 */
@ExtendWith(MockitoExtension.class)
class ProviderVerificationNewlyExpiredDocumentsTest {

    private static final String USER_ID = "provider-user-1";
    private static final String PROVIDER_ID = "provider-profile-1";
    private static final ZoneId MARKETPLACE_ZONE = ZoneId.of("Asia/Colombo");

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private VerificationDocumentRepository verificationDocumentRepository;

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    @Mock
    private VerificationAtomicUpdates verificationAtomicUpdates;

    private ProviderVerificationServiceImpl service;
    private LocalDate today;
    private Instant originallyExpiredAt;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(MARKETPLACE_ZONE);
        originallyExpiredAt = today.minusDays(5)
                .atStartOfDay(MARKETPLACE_ZONE)
                .toInstant();

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
    void shouldRequireReplacementWhenPreviouslyValidCertificateExpiresLater() {
        stubExpiredVerification();

        // ID was already expired five days ago; certificate was valid then,
        // but its expiry date passed two days ago.
        VerificationDocument oldId = document(
                "old-id", VerificationDocumentType.ID,
                today.minusDays(15), originallyExpiredAt.minusSeconds(200)
        );
        VerificationDocument certificate = document(
                "old-certificate", VerificationDocumentType.CERTIFICATE,
                today.minusDays(2), originallyExpiredAt.minusSeconds(300)
        );
        VerificationDocument renewedId = document(
                "new-id", VerificationDocumentType.ID,
                today.plusYears(2), originallyExpiredAt.plusSeconds(120)
        );

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(renewedId, certificate, oldId));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(verificationAtomicUpdates, never()).submitIfUnchanged(
                any(), anyLong(), any(), any()
        );
    }

    @Test
    void shouldAcceptOnlyCurrentValidReplacementsForBothExpiredTypes() {
        stubExpiredVerification();

        VerificationDocument oldId = document(
                "old-id", VerificationDocumentType.ID,
                today.minusDays(15), originallyExpiredAt.minusSeconds(200)
        );
        VerificationDocument certificate = document(
                "old-certificate", VerificationDocumentType.CERTIFICATE,
                today.minusDays(2), originallyExpiredAt.minusSeconds(300)
        );
        VerificationDocument renewedCertificate = document(
                "new-certificate", VerificationDocumentType.CERTIFICATE,
                today.plusYears(2), originallyExpiredAt.plusSeconds(240)
        );
        VerificationDocument renewedId = document(
                "new-id", VerificationDocumentType.ID,
                today.plusYears(2), originallyExpiredAt.plusSeconds(120)
        );

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(
                        renewedCertificate, renewedId, certificate, oldId
                ));

        ProviderVerification saved = expiredVerification();
        saved.setStatus(ProviderVerificationStatus.SUBMITTED);
        saved.setSubmittedDocumentIds(List.of("new-certificate", "new-id"));

        when(verificationAtomicUpdates.submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(7L),
                eq(List.of("new-certificate", "new-id")),
                any(Instant.class)
        )).thenReturn(Optional.of(saved));

        var response = service.submitMyVerification();

        assertEquals(ProviderVerificationStatus.SUBMITTED, response.getStatus());
        verify(verificationAtomicUpdates).submitIfUnchanged(
                eq(PROVIDER_ID),
                eq(7L),
                eq(List.of("new-certificate", "new-id")),
                any(Instant.class)
        );
    }

    @Test
    void shouldTreatCertificateExpiringTodayAsExpired() {
        stubExpiredVerification();

        VerificationDocument oldId = document(
                "old-id", VerificationDocumentType.ID,
                today.minusDays(15), originallyExpiredAt.minusSeconds(200)
        );
        VerificationDocument certificate = document(
                "old-certificate", VerificationDocumentType.CERTIFICATE,
                today, originallyExpiredAt.minusSeconds(300)
        );
        VerificationDocument renewedId = document(
                "new-id", VerificationDocumentType.ID,
                today.plusYears(2), originallyExpiredAt.plusSeconds(120)
        );

        when(verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(renewedId, certificate, oldId));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.submitMyVerification()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(verificationAtomicUpdates, never()).submitIfUnchanged(
                any(), anyLong(), any(), any()
        );
    }

    private void stubExpiredVerification() {
        when(verificationAtomicUpdates.ensureVerificationRecord(
                eq(PROVIDER_ID), any(Instant.class)
        )).thenReturn(expiredVerification());
    }

    private ProviderVerification expiredVerification() {
        ProviderVerification verification = new ProviderVerification();
        verification.setId("verification-1");
        verification.setProviderId(PROVIDER_ID);
        verification.setStatus(ProviderVerificationStatus.EXPIRED);
        verification.setExpiredAt(originallyExpiredAt);
        verification.setUpdatedAt(originallyExpiredAt);
        verification.setGracePeriodEndsAt(
                originallyExpiredAt.plusSeconds(10L * 24 * 60 * 60)
        );
        verification.setExpiredDocumentIds(List.of("old-id"));
        verification.setSubmittedDocumentIds(
                List.of("old-id", "old-certificate")
        );
        verification.setCoordinationRevision(7L);
        verification.setInFlightUploadCount(0);
        return verification;
    }

    private VerificationDocument document(
            String id,
            VerificationDocumentType type,
            LocalDate expiryDate,
            Instant uploadedAt
    ) {
        VerificationDocument document = new VerificationDocument();
        document.setId(id);
        document.setProviderId(PROVIDER_ID);
        document.setDocumentType(type);
        document.setExpiryDate(expiryDate);
        document.setUploadedAt(uploadedAt);
        return document;
    }
}
