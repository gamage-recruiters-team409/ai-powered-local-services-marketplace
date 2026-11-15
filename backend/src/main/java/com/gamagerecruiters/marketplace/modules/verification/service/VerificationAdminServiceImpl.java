package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
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
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class VerificationAdminServiceImpl implements VerificationAdminService {

    private final ProviderVerificationRepository providerVerificationRepository;

    private final VerificationDocumentRepository verificationDocumentRepository;

    private final ProviderProfileRepository providerProfileRepository;

    public VerificationAdminServiceImpl(
            ProviderVerificationRepository providerVerificationRepository,
            VerificationDocumentRepository verificationDocumentRepository,
            ProviderProfileRepository providerProfileRepository
    ) {
        this.providerVerificationRepository = providerVerificationRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.providerProfileRepository = providerProfileRepository;
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
    public AdminVerificationDetailResponse getVerificationDetails(
            String providerId
    ) {

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
    public ProviderVerificationResponse startReview(String providerId) {

        ProviderVerification verification = requireVerification(providerId);

        if (verification.getStatus() == ProviderVerificationStatus.UNDER_REVIEW) {
            return toResponse(verification);
        }

        if (verification.getStatus() != ProviderVerificationStatus.SUBMITTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This verification is not available for review"
            );
        }

        verification.setStatus(
                ProviderVerificationStatus.UNDER_REVIEW
        );

        verification.setUpdatedAt(Instant.now());

        return toResponse(
                providerVerificationRepository.save(verification)
        );
    }

    @Override
    public ProviderVerificationResponse approveVerification(String providerId) {

        return completeDecision(
                providerId,
                ProviderVerificationStatus.APPROVED,
                null,
                VerificationDocumentStatus.APPROVED
        );
    }

    @Override
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

        ProviderVerification verification = requireVerification(providerId);

        validateReviewable(verification);

        String reviewerId = CurrentUser.getUserId();

        if (reviewerId == null) {
            throw new AccessDeniedException("Authentication required");
        }

        Instant now = Instant.now();

        verification.setStatus(newStatus);
        verification.setReviewedAt(now);
        verification.setReviewedByUserId(reviewerId);
        verification.setDecisionReason(reason);
        verification.setUpdatedAt(now);

        ProviderVerification savedVerification =
                providerVerificationRepository.save(verification);

        if (documentStatus != null) {

            List<VerificationDocument> documents =
                    verificationDocumentRepository
                            .findAllByProviderIdOrderByUploadedAtDesc(providerId);

            documents.forEach(document -> {
                document.setStatus(documentStatus);
                document.setUpdatedAt(now);
            });

            verificationDocumentRepository.saveAll(documents);
        }

        return toResponse(savedVerification);
    }

    private void validateReviewable(ProviderVerification verification) {

        ProviderVerificationStatus status = verification.getStatus();

        if (
                status != ProviderVerificationStatus.SUBMITTED
                && status != ProviderVerificationStatus.UNDER_REVIEW
        ) {
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

        return providerVerificationRepository
                .findByProviderId(providerId)
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "PROVIDER_VERIFICATION_NOT_FOUND",
                                "Provider verification not found"
                        )
                );
    }

    private ProviderProfile requireProviderProfile(String providerId) {

        return providerProfileRepository
                .findById(providerId)
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "PROVIDER_PROFILE_NOT_FOUND",
                                "Provider profile not found"
                        )
                );
    }

    private AdminVerificationQueueItemResponse toQueueResponse(
            ProviderVerification verification
    ) {

        ProviderProfile profile =
                requireProviderProfile(verification.getProviderId());

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