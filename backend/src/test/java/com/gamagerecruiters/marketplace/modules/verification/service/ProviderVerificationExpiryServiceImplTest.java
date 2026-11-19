
package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;

import com.gamagerecruiters.marketplace.modules.verification.event.ProviderVerificationExpiredEvent;

import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationExpiryServiceImplTest {

    private static final String PROVIDER_ID = "provider-profile-1";

    private static final String USER_ID = "provider-user-1";

    private static final Instant NOW =
            Instant.parse("2026-11-20T04:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneId.of("Asia/Colombo"));

    @Mock
    private ProviderVerificationRepository verificationRepository;

    @Mock
    private VerificationDocumentRepository documentRepository;

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private ProviderVerificationExpiryAtomicService atomicService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ProviderVerificationExpiryServiceImpl service;

    @BeforeEach
    void setUp() {

        service = new ProviderVerificationExpiryServiceImpl(
                verificationRepository,
                documentRepository,
                providerProfileRepository,
                atomicService,
                eventPublisher,
                CLOCK,
                Duration.ofDays(10)
        );
    }

    @Test
    void shouldExpireProviderWhenApprovedDocumentExpires() {

        ProviderVerification verification = createApprovedVerification();

        when(verificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(ProviderVerificationStatus.APPROVED)
                ))
                .thenReturn(List.of(verification));

        VerificationDocument expiredDocument = createDocument(
                "document-expired",
                LocalDate.of(2026, 11, 20)
        );

        VerificationDocument validDocument = createDocument(
                "document-valid",
                LocalDate.of(2026, 11, 21)
        );

        when(documentRepository
                .findAllByIdInAndProviderIdOrderByUploadedAtDesc(
                        verification.getSubmittedDocumentIds(),
                        PROVIDER_ID
                ))
                .thenReturn(List.of(expiredDocument, validDocument));

        ProviderVerification expiredVerification =
                createApprovedVerification();

        expiredVerification.setStatus(ProviderVerificationStatus.EXPIRED);
        expiredVerification.setExpiredAt(NOW);
        expiredVerification.setGracePeriodEndsAt(
                NOW.plus(Duration.ofDays(10))
        );

        when(atomicService.expireApprovedIfUnchanged(
                eq(PROVIDER_ID),
                eq(List.of("document-expired", "document-valid")),
                eq(List.of("document-expired")),
                eq(NOW),
                eq(NOW.plus(Duration.ofDays(10)))
        )).thenReturn(Optional.of(expiredVerification));

        ProviderProfile profile = new ProviderProfile();
        profile.setId(PROVIDER_ID);
        profile.setUserId(USER_ID);

        when(providerProfileRepository.findById(PROVIDER_ID))
                .thenReturn(Optional.of(profile));

        int result = service.processExpiredVerifications();

        assertEquals(1, result);

        verify(atomicService).expireApprovedIfUnchanged(
                eq(PROVIDER_ID),
                eq(List.of("document-expired", "document-valid")),
                eq(List.of("document-expired")),
                eq(NOW),
                eq(NOW.plus(Duration.ofDays(10)))
        );

        verify(eventPublisher).publishEvent(
                any(ProviderVerificationExpiredEvent.class)
        );
    }

    @Test
    void shouldNotExpireProviderWhenAllDocumentsAreValid() {

        ProviderVerification verification = createApprovedVerification();

        when(verificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(ProviderVerificationStatus.APPROVED)
                ))
                .thenReturn(List.of(verification));

        when(documentRepository
                .findAllByIdInAndProviderIdOrderByUploadedAtDesc(
                        verification.getSubmittedDocumentIds(),
                        PROVIDER_ID
                ))
                .thenReturn(List.of(
                        createDocument(
                                "document-expired",
                                LocalDate.of(2030, 12, 31)
                        ),
                        createDocument(
                                "document-valid",
                                LocalDate.of(2031, 12, 31)
                        )
                ));

        int result = service.processExpiredVerifications();

        assertEquals(0, result);

        verify(atomicService, never()).expireApprovedIfUnchanged(
                any(), any(), any(), any(), any()
        );

        verify(eventPublisher, never()).publishEvent(
                any(ProviderVerificationExpiredEvent.class)
        );
    }

    @Test
    void shouldClearPreviousExpiryMetadataAfterSuccessfulReverification() {

        ProviderVerification verification = createApprovedVerification();

        verification.setExpiredAt(
                Instant.parse("2026-11-01T00:00:00Z")
        );

        verification.setGracePeriodEndsAt(
                Instant.parse("2026-11-11T00:00:00Z")
        );

        verification.setExpiredDocumentIds(
                List.of("document-expired")
        );

        when(verificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(ProviderVerificationStatus.APPROVED)
                ))
                .thenReturn(List.of(verification));

        when(documentRepository
                .findAllByIdInAndProviderIdOrderByUploadedAtDesc(
                        verification.getSubmittedDocumentIds(),
                        PROVIDER_ID
                ))
                .thenReturn(List.of(
                        createDocument(
                                "document-expired",
                                LocalDate.of(2030, 12, 31)
                        ),
                        createDocument(
                                "document-valid",
                                LocalDate.of(2031, 12, 31)
                        )
                ));

        service.processExpiredVerifications();

        verify(atomicService)
                .clearExpiryMetadataIfApproved(PROVIDER_ID);
    }

    @Test
    void shouldSkipExpiryWhenDocumentSnapshotIsIncomplete() {

        ProviderVerification verification = createApprovedVerification();

        when(verificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(ProviderVerificationStatus.APPROVED)
                ))
                .thenReturn(List.of(verification));

        when(documentRepository
                .findAllByIdInAndProviderIdOrderByUploadedAtDesc(
                        verification.getSubmittedDocumentIds(),
                        PROVIDER_ID
                ))
                .thenReturn(List.of(
                        createDocument(
                                "document-expired",
                                LocalDate.of(2020, 1, 1)
                        )
                ));

        int result = service.processExpiredVerifications();

        assertEquals(0, result);

        verify(atomicService, never()).expireApprovedIfUnchanged(
                any(), any(), any(), any(), any()
        );
    }

    private ProviderVerification createApprovedVerification() {

        ProviderVerification verification = new ProviderVerification();

        verification.setId("verification-1");
        verification.setProviderId(PROVIDER_ID);

        verification.setStatus(
                ProviderVerificationStatus.APPROVED
        );

        verification.setSubmittedDocumentIds(
                List.of("document-expired", "document-valid")
        );

        verification.setUpdatedAt(NOW);
        verification.setCreatedAt(NOW);
        verification.setCoordinationRevision(5L);
        verification.setInFlightUploadCount(0);

        return verification;
    }

    private VerificationDocument createDocument(
            String id,
            LocalDate expiryDate
    ) {

        VerificationDocument document = new VerificationDocument();

        document.setId(id);
        document.setProviderId(PROVIDER_ID);
        document.setExpiryDate(expiryDate);

        return document;
    }
}
