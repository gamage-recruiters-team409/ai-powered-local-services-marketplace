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
public class ProviderVerificationExpiryServiceImpl implements ProviderVerificationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderVerificationExpiryServiceImpl.class);
    private static final Duration EVENT_CLAIM_LEASE = Duration.ofMinutes(2);
    private static final Duration EVENT_RETRY_DELAY = Duration.ofMinutes(1);
    private static final int MAX_EVENTS_PER_RUN = 100;

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
            @Value("${app.provider-verification.expiry.grace-period:P10D}") Duration gracePeriod,
            @Value("${app.provider-verification.expiry.zone:Asia/Colombo}") String zoneId
    ) {
        this(verificationRepository, documentRepository, providerProfileRepository,
                atomicService, eventPublisher, Clock.system(ZoneId.of(zoneId)), gracePeriod);
    }

    // Package-private for deterministic unit tests.
    ProviderVerificationExpiryServiceImpl(
            ProviderVerificationRepository verificationRepository,
            VerificationDocumentRepository documentRepository,
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationExpiryAtomicService atomicService,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Duration gracePeriod
    ) {
        if (gracePeriod == null || gracePeriod.isZero() || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("Verification expiry grace period must be positive");
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
        int expiredCount = 0;

        try {
            List<ProviderVerification> approvedVerifications =
                    verificationRepository.findAllByStatusInOrderBySubmittedAtAsc(
                            List.of(ProviderVerificationStatus.APPROVED));

            for (ProviderVerification verification : approvedVerifications) {
                try {
                    String providerId = verification.getProviderId();
                    List<String> submittedDocumentIds = verification.getSubmittedDocumentIds();
                    if (submittedDocumentIds == null || submittedDocumentIds.isEmpty()) {
                        log.warn("Skipping expiry check: no submitted documents for provider {}", providerId);
                        continue;
                    }

                    List<VerificationDocument> documents = documentRepository
                            .findAllByIdInAndProviderIdOrderByUploadedAtDesc(
                                    submittedDocumentIds, providerId);

                    if (documents.size() != submittedDocumentIds.size()) {
                        log.warn("Skipping expiry check: incomplete document snapshot for provider {}", providerId);
                        continue;
                    }

                    List<String> expiredDocumentIds = documents.stream()
                            .filter(document -> document.getExpiryDate() != null
                                    && !document.getExpiryDate().isAfter(today))
                            .map(VerificationDocument::getId)
                            .filter(id -> id != null && !id.isBlank())
                            .distinct()
                            .toList();

                    if (expiredDocumentIds.isEmpty()) {
                        if (verification.getExpiredAt() != null) {
                            atomicService.clearExpiryMetadataIfApproved(providerId);
                        }
                        continue;
                    }

                    Instant gracePeriodEndsAt = now.plus(gracePeriod);
                    Optional<ProviderVerification> updated = atomicService.expireApprovedIfUnchanged(
                            providerId, submittedDocumentIds, expiredDocumentIds, now,
                            gracePeriodEndsAt);
                    if (updated.isEmpty()) {
                        continue;
                    }
                    expiredCount++;
                    log.info("Provider verification expired: providerId={}, gracePeriodEndsAt={}",
                            providerId, gracePeriodEndsAt);
                } catch (RuntimeException exception) {
                    log.error("Expiry processing failed for provider {}",
                            verification.getProviderId(), exception);
                }
            }
        } finally {
            // Pending events survive restarts and are retried even when status is already EXPIRED.
            recoverPendingExpiryEvents();
        }

        return expiredCount;
    }

    private void recoverPendingExpiryEvents() {
        for (int processed = 0; processed < MAX_EVENTS_PER_RUN; processed++) {
            Optional<ProviderVerification> claimed;
            try {
                claimed = atomicService.claimNextPendingExpiryEvent(
                        clock.instant(), EVENT_CLAIM_LEASE);
            } catch (RuntimeException exception) {
                log.error("Failed to claim pending provider verification expiry event", exception);
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            publishClaimedExpiryEvent(claimed.get());
        }
    }

    private void publishClaimedExpiryEvent(ProviderVerification verification) {
        String providerId = verification.getProviderId();
        String claimToken = verification.getExpiryEventClaimToken();
        try {
            ProviderProfile profile = providerProfileRepository.findById(providerId)
                    .filter(p -> p.getUserId() != null && !p.getUserId().isBlank())
                    .orElseThrow(() -> new IllegalStateException(
                            "Provider profile unavailable for expiry event: " + providerId));

            ProviderVerificationExpiredEvent event = new ProviderVerificationExpiredEvent(
                    providerId,
                    profile.getUserId(),
                    verification.getId(),
                    verification.getExpiredAt(),
                    verification.getGracePeriodEndsAt(),
                    verification.getExpiredDocumentIds()
            );

            eventPublisher.publishEvent(event);
            boolean acknowledged = atomicService.markExpiryEventPublished(
                    providerId, claimToken, clock.instant());
            if (!acknowledged) {
                log.warn("Expiry event published but claim acknowledgement was not applied for {}", providerId);
            }
        } catch (RuntimeException exception) {
            log.error("Expiry event publishing failed; pending event will be retried for {}",
                    providerId, exception);
            try {
                atomicService.deferExpiryEvent(
                        providerId, claimToken, clock.instant().plus(EVENT_RETRY_DELAY));
            } catch (RuntimeException deferException) {
                log.error("Could not defer failed expiry event claim for {}; lease recovery will retry",
                        providerId, deferException);
            }
        }
    }
}
