package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class ProviderVerificationServiceImpl
        implements ProviderVerificationService {

    private static final ZoneId MARKETPLACE_ZONE =
            ZoneId.of("Asia/Colombo");

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

        // Recover expired reservations before reading the revision and documents.
        verificationAtomicUpdates.recoverExpiredUploadReservations(
                providerId,
                now
        );

        ProviderVerification current =
                verificationAtomicUpdates.ensureVerificationRecord(
                        providerId,
                        now
                );

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

        List<VerificationDocument> submissionDocuments =
                resolveSubmissionDocuments(current, documents);

        List<String> submittedDocumentIds = submissionDocuments.stream()
                .map(VerificationDocument::getId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        if (submittedDocumentIds.size() != submissionDocuments.size()) {
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
                    verification.getExpiredAt() != null
                            ? verification.getExpiredAt()
                            : verification.getUpdatedAt()
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
                // Already handled before reading the document set.
            }
        }
    }

    /**
     * Preserves the existing submission behaviour for normal submissions
     * and normal rejection/resubmission flows.
     *
     * For expiry-related re-verification:
     * - Keeps previously submitted, still-valid documents.
     * - Removes expired documents.
     * - Replaces old documents when a newer document of the same type
     *   has been uploaded.
     * - Requires replacement documents for every expired document type.
     */
    private List<VerificationDocument> resolveSubmissionDocuments(
            ProviderVerification verification,
            List<VerificationDocument> documents
    ) {

        List<String> recordedExpiredIds =
                verification.getExpiredDocumentIds();

        boolean expiryReverification =
                verification.getStatus() == ProviderVerificationStatus.EXPIRED
                        || !recordedExpiredIds.isEmpty();

        // Preserve existing behaviour outside expiry-related resubmissions.
        if (!expiryReverification) {
            return documents;
        }

        Instant previousDecisionTime;

        if (verification.getStatus() == ProviderVerificationStatus.EXPIRED) {

            previousDecisionTime = verification.getExpiredAt() != null
                    ? verification.getExpiredAt()
                    : verification.getUpdatedAt();

        } else {

            previousDecisionTime = verification.getReviewedAt();
        }

        if (previousDecisionTime == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Previous verification decision timestamp is unavailable"
            );
        }

        List<String> previousSnapshotIds =
                verification.getSubmittedDocumentIds();

        if (previousSnapshotIds == null || previousSnapshotIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Previous approved document snapshot is unavailable. Please contact support"
            );
        }

        Map<String, VerificationDocument> documentsById =
                new HashMap<>();

        for (VerificationDocument document : documents) {

            if (document.getId() != null
                    && !document.getId().isBlank()) {

                documentsById.putIfAbsent(
                        document.getId(),
                        document
                );
            }
        }

        LocalDate today = LocalDate.now(MARKETPLACE_ZONE);

        Set<String> expiredIds =
                new LinkedHashSet<>(recordedExpiredIds);

        // IMPORTANT: The recorded expired IDs are only a snapshot of the
        // original expiry event. Additional previously approved documents
        // may expire before this provider actually resubmits.
        // Revalidate every document in the exact previous submission against
        // today's date, including documents not in the original expired list.
        for (String documentId : previousSnapshotIds) {

            VerificationDocument previousDocument =
                    documentsById.get(documentId);

            if (previousDocument == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A previously submitted verification document could not be found"
                );
            }

            if (previousDocument.getExpiryDate() != null
                    && !previousDocument.getExpiryDate().isAfter(today)) {

                expiredIds.add(documentId);
            }
        }

        if (expiredIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Expired document information is unavailable. Please refresh and try again"
            );
        }

        Set<VerificationDocumentType> expiredTypes =
                new HashSet<>();

        for (String expiredId : expiredIds) {

            VerificationDocument expiredDocument =
                    documentsById.get(expiredId);

            if (expiredDocument == null
                    || expiredDocument.getDocumentType() == null) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "An expired verification document could not be identified"
                );
            }

            expiredTypes.add(expiredDocument.getDocumentType());
        }

        // Identify valid replacement documents uploaded after expiry
        // or after the latest Admin resubmission/rejection decision.
        List<VerificationDocument> replacementDocuments =
                documents.stream()
                        .filter(document ->
                                document.getUploadedAt() != null
                                        && document.getUploadedAt()
                                                .isAfter(previousDecisionTime)
                        )
                        .filter(document ->
                                document.getDocumentType() != null
                        )
                        .filter(document ->
                                document.getExpiryDate() == null
                                        || document.getExpiryDate()
                                                .isAfter(today)
                        )
                        .toList();

        Set<VerificationDocumentType> replacementTypes =
                new HashSet<>();

        replacementDocuments.forEach(document ->
                replacementTypes.add(document.getDocumentType())
        );

        // Every expired document type must receive a valid replacement.
        if (!replacementTypes.containsAll(expiredTypes)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Upload renewed documents for every expired document type before resubmitting"
            );
        }

        // Load the exact previously submitted document snapshot.
        List<VerificationDocument> previousSnapshot =
                new ArrayList<>();

        for (String documentId : previousSnapshotIds) {

            VerificationDocument document =
                    documentsById.get(documentId);

            if (document == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A previously submitted verification document could not be found"
                );
            }

            previousSnapshot.add(document);
        }

        Map<String, VerificationDocument> selectedDocuments =
                new LinkedHashMap<>();

        // Preserve valid existing documents that are not being replaced.
        for (VerificationDocument previousDocument : previousSnapshot) {

            if (expiredIds.contains(previousDocument.getId())) {
                continue;
            }

            if (replacementTypes.contains(
                    previousDocument.getDocumentType()
            )) {
                continue;
            }

            selectedDocuments.put(
                    previousDocument.getId(),
                    previousDocument
            );
        }

        // Add the new replacement documents.
        for (VerificationDocument replacement : replacementDocuments) {

            selectedDocuments.put(
                    replacement.getId(),
                    replacement
            );
        }

        return List.copyOf(selectedDocuments.values());
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

        return providerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));
    }

    private ProviderVerificationResponse toResponse(
            ProviderVerification verification
    ) {

        boolean expired =
                verification.getStatus()
                        == ProviderVerificationStatus.EXPIRED;

        return new ProviderVerificationResponse(
                verification.getId(),
                verification.getStatus(),
                verification.getSubmittedAt(),
                verification.getReviewedAt(),
                verification.getDecisionReason(),
                verification.getUpdatedAt(),
                expired ? verification.getExpiredAt() : null,
                expired ? verification.getGracePeriodEndsAt() : null
        );
    }
}
