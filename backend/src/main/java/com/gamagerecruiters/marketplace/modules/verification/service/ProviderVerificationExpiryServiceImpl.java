
package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;

import com.gamagerecruiters.marketplace.modules.verification.event.ProviderVerificationExpiredEvent;

import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class ProviderVerificationExpiryServiceImpl
        implements ProviderVerificationExpiryService {

    private static final Logger log =
            LoggerFactory.getLogger(ProviderVerificationExpiryServiceImpl.class);

    private final ProviderVerificationRepository verificationRepository;

    private final VerificationDocumentRepository documentRepository;

    private final ProviderProfileRepository providerProfileRepository;

    private final ProviderVerificationExpiryAtomicService atomicService;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    private final Duration gracePeriod;

    @Autowired
    public ProviderVerificationExpiryServiceImpl(
            ProviderVerificationRepository verificationRepository,
            VerificationDocumentRepository documentRepository,
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationExpiryAtomicService atomicService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.provider-verification.expiry.grace-period:P10D}")
            Duration gracePeriod,
            @Value("${app.provider-verification.expiry.zone:Asia/Colombo}")
            String zoneId
    ) {
        this(
                verificationRepository,
                documentRepository,
                providerProfileRepository,
                atomicService,
                eventPublisher,
                Clock.system(ZoneId.of(zoneId)),
                gracePeriod
        );
    }

    // Constructor used by unit tests.
    ProviderVerificationExpiryServiceImpl(
            ProviderVerificationRepository verificationRepository,
            VerificationDocumentRepository documentRepository,
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationExpiryAtomicService atomicService,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Duration gracePeriod
    ) {

        if (gracePeriod == null
                || gracePeriod.isZero()
                || gracePeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "Verification expiry grace period must be positive"
            );
        }

        this.verificationRepository = verificationRepository;
        this.documentRepository = documentRepository;
        this.providerProfileRepository = providerProfileRepository;
        this.atomicService = atomicService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.gracePeriod = gracePeriod;
    }

    @Override
    public int processExpiredVerifications() {

        Instant now = clock.instant();

        LocalDate today = LocalDate.now(clock);

        List<ProviderVerification> approvedVerifications =
                verificationRepository.findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(ProviderVerificationStatus.APPROVED)
                );

        int expiredCount = 0;

        for (ProviderVerification verification : approvedVerifications) {

            try {

                String providerId = verification.getProviderId();

                List<String> submittedDocumentIds =
                        verification.getSubmittedDocumentIds();

                if (submittedDocumentIds == null
                        || submittedDocumentIds.isEmpty()) {

                    log.warn(
                            "Skipping expiry check: no submitted documents for provider {}",
                            providerId
                    );

                    continue;
                }

                List<VerificationDocument> documents =
                        documentRepository
                                .findAllByIdInAndProviderIdOrderByUploadedAtDesc(
                                        submittedDocumentIds,
                                        providerId
                                );

                // Never expire using an incomplete document snapshot.
                if (documents.size() != submittedDocumentIds.size()) {

                    log.warn(
                            "Skipping expiry check: incomplete document snapshot for provider {}",
                            providerId
                    );

                    continue;
                }

                List<String> expiredDocumentIds = documents.stream()
                        .filter(document ->
                                document.getExpiryDate() != null
                                        && !document.getExpiryDate().isAfter(today)
                        )
                        .map(VerificationDocument::getId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();

                if (expiredDocumentIds.isEmpty()) {

                    // Clear old expiry metadata after successful re-verification.
                    if (verification.getExpiredAt() != null) {

                        atomicService.clearExpiryMetadataIfApproved(
                                providerId
                        );
                    }

                    continue;
                }

                Instant gracePeriodEndsAt = now.plus(gracePeriod);

                Optional<ProviderVerification> updated =
                        atomicService.expireApprovedIfUnchanged(
                                providerId,
                                submittedDocumentIds,
                                expiredDocumentIds,
                                now,
                                gracePeriodEndsAt
                        );

                // Another operation may have changed the verification.
                if (updated.isEmpty()) {
                    continue;
                }

                expiredCount++;

                publishExpiryEvent(
                        updated.get(),
                        expiredDocumentIds,
                        now,
                        gracePeriodEndsAt
                );

                log.info(
                        "Provider verification expired: providerId={}, gracePeriodEndsAt={}",
                        providerId,
                        gracePeriodEndsAt
                );

            } catch (RuntimeException exception) {

                log.error(
                        "Expiry processing failed for provider {}",
                        verification.getProviderId(),
                        exception
                );
            }
        }

        return expiredCount;
    }

    private void publishExpiryEvent(
            ProviderVerification verification,
            List<String> expiredDocumentIds,
            Instant expiredAt,
            Instant gracePeriodEndsAt
    ) {

        String providerId = verification.getProviderId();

        Optional<ProviderProfile> profile =
                providerProfileRepository.findById(providerId);

        if (profile.isEmpty()
                || profile.get().getUserId() == null
                || profile.get().getUserId().isBlank()) {

            log.warn(
                    "Could not publish expiry event: provider profile unavailable for {}",
                    providerId
            );

            return;
        }

        ProviderVerificationExpiredEvent event =
                new ProviderVerificationExpiredEvent(
                        providerId,
                        profile.get().getUserId(),
                        verification.getId(),
                        expiredAt,
                        gracePeriodEndsAt,
                        expiredDocumentIds
                );

        try {

            eventPublisher.publishEvent(event);

        } catch (RuntimeException exception) {

            log.error(
                    "Expiry event publication failed for provider {}",
                    providerId,
                    exception
            );
        }
    }
}
