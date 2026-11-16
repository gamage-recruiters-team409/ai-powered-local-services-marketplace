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
    private final VerificationAtomicUpdates verificationAtomicUpdates;

    public ProviderVerificationServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            VerificationDocumentRepository verificationDocumentRepository,
            ProviderVerificationRepository providerVerificationRepository,
            VerificationAtomicUpdates verificationAtomicUpdates
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.providerVerificationRepository = providerVerificationRepository;
        this.verificationAtomicUpdates = verificationAtomicUpdates;
    }

    @Override
    public ProviderVerificationResponse submitMyVerification() {
        ProviderProfile providerProfile = requireCurrentProviderProfile();
        String providerId = providerProfile.getId();
        Instant now = Instant.now();

        ProviderVerification current =
                verificationAtomicUpdates.ensureVerificationRecord(providerId, now);

        if (current.getStatus() == ProviderVerificationStatus.SUBMITTED) {
            return toResponse(current);
        }

        List<VerificationDocument> documents =
                verificationDocumentRepository
                        .findAllByProviderIdOrderByUploadedAtDesc(providerId);

        if (documents.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Upload verification documents before submitting"
            );
        }

        validateSubmission(current, documents);

        List<String> submittedDocumentIds = documents.stream()
                .map(VerificationDocument::getId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (submittedDocumentIds.size() != documents.size()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "One or more verification documents could not be identified. Please refresh and try again"
            );
        }

        var saved = verificationAtomicUpdates.submitIfUnchanged(
                providerId,
                current.getCoordinationRevision(),
                submittedDocumentIds,
                now
        );

        if (saved.isPresent()) {
            return toResponse(saved.get());
        }

        // Another submission may have completed while this request was running.
        ProviderVerification latest =
                providerVerificationRepository.findByProviderId(providerId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Verification changed during submission. Please try again"
                        ));

        if (latest.getStatus() == ProviderVerificationStatus.SUBMITTED) {
            return toResponse(latest);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Verification documents changed or an upload is still in progress. Please try submitting again"
        );
    }

    @Override
    public ProviderVerificationResponse getMyVerificationStatus() {
        ProviderProfile providerProfile = requireCurrentProviderProfile();

        return providerVerificationRepository
                .findByProviderId(providerProfile.getId())
                .map(this::toResponse)
                .orElseGet(() -> new ProviderVerificationResponse(
                        null,
                        ProviderVerificationStatus.NOT_SUBMITTED,
                        null,
                        null,
                        null,
                        null
                ));
    }

    private void validateSubmission(
            ProviderVerification verification,
            List<VerificationDocument> documents
    ) {
        ProviderVerificationStatus status = verification.getStatus();

        if (status == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Provider verification status is unavailable"
            );
        }

        switch (status) {
            case NOT_SUBMITTED -> {
                // Initial submission is allowed.
            }
            case REJECTED, RESUBMISSION_REQUESTED -> requireUpdatedDocuments(
                    documents,
                    verification.getReviewedAt()
            );
            case EXPIRED -> requireUpdatedDocuments(
                    documents,
                    verification.getUpdatedAt()
            );
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
                // Handled before reading the document set.
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

        boolean hasUpdatedDocument = documents.stream()
                .anyMatch(document ->
                        document.getUploadedAt() != null
                                && document.getUploadedAt().isAfter(previousDecisionTime)
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
            throw new AccessDeniedException("Authentication required");
        }

        return providerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));
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
