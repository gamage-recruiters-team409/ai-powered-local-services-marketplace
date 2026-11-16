package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationDetailResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationQueueItemResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentAccessResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class VerificationAdminServiceImpl implements VerificationAdminService {

    private static final Duration DOCUMENT_ACCESS_URL_TTL = Duration.ofMinutes(5);

    private final ProviderVerificationRepository providerVerificationRepository;
    private final VerificationDocumentRepository verificationDocumentRepository;
    private final ProviderProfileRepository providerProfileRepository;
    private final VerificationFileStorageService verificationFileStorageService;
    private final VerificationAtomicUpdates verificationAtomicUpdates;

    public VerificationAdminServiceImpl(
            ProviderVerificationRepository providerVerificationRepository,
            VerificationDocumentRepository verificationDocumentRepository,
            ProviderProfileRepository providerProfileRepository,
            VerificationFileStorageService verificationFileStorageService,
            VerificationAtomicUpdates verificationAtomicUpdates
    ) {
        this.providerVerificationRepository = providerVerificationRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.providerProfileRepository = providerProfileRepository;
        this.verificationFileStorageService = verificationFileStorageService;
        this.verificationAtomicUpdates = verificationAtomicUpdates;
    }

    @Override
    public List<AdminVerificationQueueItemResponse> getPendingVerifications() {
        return providerVerificationRepository
                .findAllByStatusInOrderBySubmittedAtAsc(
                        List.of(
                                ProviderVerificationStatus.SUBMITTED,
                                ProviderVerificationStatus.UNDER_REVIEW
                        )
                )
                .stream()
                .map(this::toQueueResponse)
                .toList();
    }

    @Override
    public AdminVerificationDetailResponse getVerificationDetails(String providerId) {
        ProviderVerification verification = requireVerification(providerId);
        ProviderProfile profile = requireProviderProfile(providerId);

        List<VerificationDocumentResponse> documents =
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(providerId)
                        .stream()
                        .map(this::toDocumentResponse)
                        .toList();

        return new AdminVerificationDetailResponse(
                providerId,
                verification.getId(),
                profile.getDisplayName(),
                profile.getProviderType(),
                profile.getDistrict(),
                profile.getCity(),
                profile.getBio(),
                profile.getExperienceYears(),
                verification.getStatus(),
                verification.getSubmittedAt(),
                verification.getReviewedAt(),
                verification.getDecisionReason(),
                documents
        );
    }

    @Override
    public VerificationDocumentAccessResponse getVerificationDocumentAccess(
            String providerId,
            String documentId
    ) {
        requireVerification(providerId);

        VerificationDocument document =
                verificationDocumentRepository
                        .findByIdAndProviderId(documentId, providerId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "VERIFICATION_DOCUMENT_NOT_FOUND",
                                "Verification document not found"
                        ));

        Instant expiresAt = Instant.now().plus(DOCUMENT_ACCESS_URL_TTL);

        String accessUrl =
                verificationFileStorageService.generateTemporaryAccessUrl(
                        document.getStorageKey(),
                        document.getFormat(),
                        document.getResourceType(),
                        document.getDeliveryType(),
                        expiresAt
                );

        return new VerificationDocumentAccessResponse(
                document.getId(),
                document.getOriginalFileName(),
                document.getFormat(),
                accessUrl,
                expiresAt
        );
    }

    @Override
    public ProviderVerificationResponse startReview(String providerId) {
        var updated = verificationAtomicUpdates.startReview(
                providerId,
                Instant.now()
        );

        if (updated.isPresent()) {
            return toResponse(updated.get());
        }

        // Idempotent for a request that another Admin already started.
        ProviderVerification current = requireVerification(providerId);

        if (current.getStatus() == ProviderVerificationStatus.UNDER_REVIEW) {
            return toResponse(current);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This verification is not available for review"
        );
    }

    @Override
    @Transactional
    public ProviderVerificationResponse approveVerification(String providerId) {
        return completeDecision(
                providerId,
                ProviderVerificationStatus.APPROVED,
                null,
                VerificationDocumentStatus.APPROVED
        );
    }

    @Override
    @Transactional
    public ProviderVerificationResponse rejectVerification(
            String providerId,
            String reason
    ) {
        return completeDecision(
                providerId,
                ProviderVerificationStatus.REJECTED,
                validateReason(reason),
                VerificationDocumentStatus.REJECTED
        );
    }

    @Override
    @Transactional
    public ProviderVerificationResponse requestResubmission(
            String providerId,
            String reason
    ) {
        return completeDecision(
                providerId,
                ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                validateReason(reason),
                null
        );
    }

    private ProviderVerificationResponse completeDecision(
            String providerId,
            ProviderVerificationStatus newStatus,
            String reason,
            VerificationDocumentStatus documentStatus
    ) {
        ProviderVerification current = requireVerification(providerId);
        validateReviewable(current);

        List<String> submittedDocumentIds = current.getSubmittedDocumentIds();

        if (submittedDocumentIds == null || submittedDocumentIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The submitted verification document snapshot is unavailable. The provider must submit the verification again"
            );
        }

        String reviewerId = CurrentUser.getUserId();
        if (reviewerId == null) {
            throw new AccessDeniedException("Authentication required");
        }

        Instant now = Instant.now();

        ProviderVerification updated =
                verificationAtomicUpdates.applyDecision(
                        providerId,
                        submittedDocumentIds,
                        newStatus,
                        reviewerId,
                        reason,
                        now
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This verification has already been processed or its submitted documents have changed"
                ));

        if (documentStatus != null) {
            long matchedDocuments =
                    verificationAtomicUpdates.updateSubmittedDocumentStatuses(
                            providerId,
                            submittedDocumentIds,
                            documentStatus,
                            now
                    );

            if (matchedDocuments != submittedDocumentIds.size()) {
                // Runtime exception causes the Mongo transaction to roll back
                // both the verification decision and document status changes.
                throw new IllegalStateException(
                        "Verification decision was not completed because one or more submitted documents could not be updated"
                );
            }
        }

        return toResponse(updated);
    }

    private void validateReviewable(ProviderVerification verification) {
        ProviderVerificationStatus status = verification.getStatus();

        if (status != ProviderVerificationStatus.SUBMITTED
                && status != ProviderVerificationStatus.UNDER_REVIEW) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This verification has already been processed or is not ready for review"
            );
        }
    }

    private String validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A decision reason is required"
            );
        }

        String trimmed = reason.trim();

        if (trimmed.length() > 1000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Decision reason must not exceed 1000 characters"
            );
        }

        return trimmed;
    }

    private ProviderVerification requireVerification(String providerId) {
        return providerVerificationRepository.findByProviderId(providerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_VERIFICATION_NOT_FOUND",
                        "Provider verification not found"
                ));
    }

    private ProviderProfile requireProviderProfile(String providerId) {
        return providerProfileRepository.findById(providerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));
    }

    private AdminVerificationQueueItemResponse toQueueResponse(
            ProviderVerification verification
    ) {
        ProviderProfile profile = requireProviderProfile(verification.getProviderId());

        long documentCount =
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(
                                verification.getProviderId()
                        )
                        .size();

        return new AdminVerificationQueueItemResponse(
                verification.getProviderId(),
                verification.getId(),
                profile.getDisplayName(),
                profile.getProviderType(),
                profile.getDistrict(),
                profile.getCity(),
                verification.getStatus(),
                verification.getSubmittedAt(),
                documentCount
        );
    }

    private VerificationDocumentResponse toDocumentResponse(
            VerificationDocument document
    ) {
        return new VerificationDocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getOriginalFileName(),
                document.getFormat(),
                document.getBytes(),
                document.getIssueDate(),
                document.getExpiryDate(),
                document.getStatus(),
                document.getUploadedAt()
        );
    }

    private ProviderVerificationResponse toResponse(
            ProviderVerification verification
    ) {
        return new ProviderVerificationResponse(
                verification.getId(),
                verification.getStatus(),
                verification.getSubmittedAt(),
                verification.getReviewedAt(),
                verification.getDecisionReason(),
                verification.getUpdatedAt()
        );
    }
}
