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
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class ProviderVerificationServiceImpl
        implements ProviderVerificationService {

    private final ProviderProfileRepository providerProfileRepository;

    private final VerificationDocumentRepository verificationDocumentRepository;

    private final ProviderVerificationRepository providerVerificationRepository;

    public ProviderVerificationServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            VerificationDocumentRepository verificationDocumentRepository,
            ProviderVerificationRepository providerVerificationRepository
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.providerVerificationRepository = providerVerificationRepository;
    }

    @Override
    public ProviderVerificationResponse submitMyVerification() {

        ProviderProfile providerProfile =
                requireCurrentProviderProfile();

        String providerId = providerProfile.getId();

        List<VerificationDocument> documents =
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(
                                providerId
                        );

        if (documents.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Upload verification documents before submitting"
            );
        }

        ProviderVerification verification =
                providerVerificationRepository
                        .findByProviderId(providerId)
                        .orElse(null);

        Instant now = Instant.now();

        // First-time verification submission.
        if (verification == null) {

            verification = new ProviderVerification();

            verification.setProviderId(providerId);

            verification.setCreatedAt(now);

        } else {

            // Avoid duplicate submissions.
            if (
                    verification.getStatus()
                            == ProviderVerificationStatus.SUBMITTED
            ) {
                return toResponse(verification);
            }

            validateSubmission(verification, documents);
        }

        verification.setStatus(
                ProviderVerificationStatus.SUBMITTED
        );

        verification.setSubmittedAt(now);

        // Clear previous review information on resubmission.
        verification.setReviewedAt(null);

        verification.setReviewedByUserId(null);

        verification.setDecisionReason(null);

        verification.setUpdatedAt(now);

        ProviderVerification savedVerification =
                providerVerificationRepository.save(
                        verification
                );

        return toResponse(savedVerification);
    }

    @Override
    public ProviderVerificationResponse getMyVerificationStatus() {

        ProviderProfile providerProfile =
                requireCurrentProviderProfile();

        return providerVerificationRepository
                .findByProviderId(providerProfile.getId())
                .map(this::toResponse)
                .orElseGet(
                        () -> new ProviderVerificationResponse(
                                null,
                                ProviderVerificationStatus.NOT_SUBMITTED,
                                null,
                                null,
                                null,
                                null
                        )
                );
    }

    private void validateSubmission(
            ProviderVerification verification,
            List<VerificationDocument> documents
    ) {

        ProviderVerificationStatus currentStatus =
                verification.getStatus();

        if (currentStatus == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Provider verification status is unavailable"
            );
        }

        switch (currentStatus) {

            case NOT_SUBMITTED -> {
                // Initial submission is allowed.
            }

            case REJECTED, RESUBMISSION_REQUESTED -> {

                requireUpdatedDocuments(
                        documents,
                        verification.getReviewedAt()
                );
            }

            case EXPIRED -> {

                requireUpdatedDocuments(
                        documents,
                        verification.getUpdatedAt()
                );
            }

            case UNDER_REVIEW -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Verification is already under review"
            );

            case APPROVED -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your verification is already approved"
            );

            case SUSPENDED -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your verification is suspended. Contact support"
            );

            case SUBMITTED -> {
                // Handled before reaching this method.
            }
        }
    }

    private void requireUpdatedDocuments(
            List<VerificationDocument> documents,
            Instant previousDecisionTime
    ) {

        if (previousDecisionTime == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Previous verification decision timestamp is unavailable"
            );
        }

        boolean hasUpdatedDocument =
                documents.stream()
                        .anyMatch(document ->
                                document.getUploadedAt() != null
                                        && document.getUploadedAt()
                                                .isAfter(previousDecisionTime)
                        );

        if (!hasUpdatedDocument) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Upload at least one updated verification document before resubmitting"
            );
        }
    }

    private ProviderProfile requireCurrentProviderProfile() {

        String userId = CurrentUser.getUserId();

        if (userId == null) {
            throw new AccessDeniedException(
                    "Authentication required"
            );
        }

        return providerProfileRepository
                .findByUserId(userId)
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "PROVIDER_PROFILE_NOT_FOUND",
                                "Provider profile not found"
                        )
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