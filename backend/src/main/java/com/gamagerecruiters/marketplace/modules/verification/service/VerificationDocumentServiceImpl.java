package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.StoredVerificationFile;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class VerificationDocumentServiceImpl
        implements VerificationDocumentService {

    private final ProviderProfileRepository providerProfileRepository;
    private final VerificationDocumentRepository verificationDocumentRepository;
    private final VerificationAtomicUpdates verificationAtomicUpdates;
    private final VerificationFileStorageService verificationFileStorageService;

    public VerificationDocumentServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            VerificationDocumentRepository verificationDocumentRepository,
            VerificationAtomicUpdates verificationAtomicUpdates,
            VerificationFileStorageService verificationFileStorageService
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.verificationAtomicUpdates = verificationAtomicUpdates;
        this.verificationFileStorageService = verificationFileStorageService;
    }

    @Override
    public VerificationDocumentResponse uploadMyDocument(
            VerificationDocumentType documentType,
            LocalDate issueDate,
            LocalDate expiryDate,
            MultipartFile file
    ) {
        ProviderProfile providerProfile = requireCurrentProviderProfile();
        String providerId = providerProfile.getId();

        validateDocumentMetadata(documentType, issueDate, expiryDate);

        // Reserve before Cloudinary. Submission/review cannot pass this reservation.
        verificationAtomicUpdates.reserveDocumentUpload(providerId, Instant.now());

        StoredVerificationFile storedFile = null;
        VerificationDocument savedDocument;
        RuntimeException operationFailure = null;

        try {
            storedFile = verificationFileStorageService.upload(providerId, file);

            VerificationDocument document = new VerificationDocument();
            document.setProviderId(providerId);
            document.setDocumentType(documentType);
            document.setStorageKey(storedFile.publicId());
            document.setAssetId(storedFile.assetId());
            document.setResourceType(storedFile.resourceType());
            document.setDeliveryType(storedFile.deliveryType());
            document.setFormat(storedFile.format());
            document.setBytes(storedFile.bytes() == null ? 0L : storedFile.bytes());
            document.setOriginalFileName(storedFile.originalFileName());
            document.setIssueDate(issueDate);
            document.setExpiryDate(expiryDate);
            document.setStatus(VerificationDocumentStatus.SUBMITTED);

            Instant now = Instant.now();
            document.setUploadedAt(now);
            document.setUpdatedAt(now);

            savedDocument = verificationDocumentRepository.save(document);
        } catch (RuntimeException exception) {
            operationFailure = exception;

            if (storedFile != null) {
                try {
                    verificationFileStorageService.delete(storedFile.publicId());
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }

            throw exception;
        } finally {
            try {
                verificationAtomicUpdates.releaseDocumentUpload(providerId);
            } catch (RuntimeException releaseException) {
                if (operationFailure != null) {
                    operationFailure.addSuppressed(releaseException);
                } else {
                    throw releaseException;
                }
            }
        }

        return toResponse(savedDocument);
    }

    @Override
    public List<VerificationDocumentResponse> getMyDocuments() {
        ProviderProfile providerProfile = requireCurrentProviderProfile();

        return verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(providerProfile.getId())
                .stream()
                .map(this::toResponse)
                .toList();
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

    private void validateDocumentMetadata(
            VerificationDocumentType documentType,
            LocalDate issueDate,
            LocalDate expiryDate
    ) {
        if (documentType == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verification document type is required"
            );
        }

        if (issueDate != null && expiryDate != null
                && expiryDate.isBefore(issueDate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Expiry date cannot be before issue date"
            );
        }
    }

    private VerificationDocumentResponse toResponse(
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
}
