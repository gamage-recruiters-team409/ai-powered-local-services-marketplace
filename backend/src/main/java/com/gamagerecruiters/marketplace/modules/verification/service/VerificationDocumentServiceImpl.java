
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
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.StoredVerificationFile;
import com.gamagerecruiters.marketplace.modules.verification.filestorage.VerificationFileStorageService;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.repository.VerificationDocumentRepository;

@Service
public class VerificationDocumentServiceImpl
        implements VerificationDocumentService {

    private final ProviderProfileRepository providerProfileRepository;

    private final VerificationDocumentRepository verificationDocumentRepository;

    private final ProviderVerificationRepository providerVerificationRepository;

    private final VerificationFileStorageService verificationFileStorageService;

    public VerificationDocumentServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            VerificationDocumentRepository verificationDocumentRepository,
            ProviderVerificationRepository providerVerificationRepository,
            VerificationFileStorageService verificationFileStorageService
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.providerVerificationRepository = providerVerificationRepository;
        this.verificationFileStorageService = verificationFileStorageService;
    }

    @Override
    public VerificationDocumentResponse uploadMyDocument(
            VerificationDocumentType documentType,
            LocalDate issueDate,
            LocalDate expiryDate,
            MultipartFile file
    ) {

        ProviderProfile providerProfile =
                requireCurrentProviderProfile();

        validateDocumentMetadata(
                documentType,
                issueDate,
                expiryDate
        );

        // Check verification status before uploading to Cloudinary.
        validateDocumentUploadPermission(
                providerProfile.getId()
        );

        StoredVerificationFile storedFile =
                verificationFileStorageService.upload(
                        providerProfile.getId(),
                        file
                );

        VerificationDocument document =
                new VerificationDocument();

        document.setProviderId(
                providerProfile.getId()
        );

        document.setDocumentType(
                documentType
        );

        // Store Cloudinary's private public ID internally.
        document.setStorageKey(
                storedFile.publicId()
        );

        document.setAssetId(
                storedFile.assetId()
        );

        document.setResourceType(
                storedFile.resourceType()
        );

        document.setDeliveryType(
                storedFile.deliveryType()
        );

        document.setFormat(
                storedFile.format()
        );

        document.setBytes(
                storedFile.bytes() == null
                        ? 0L
                        : storedFile.bytes()
        );

        document.setOriginalFileName(
                storedFile.originalFileName()
        );

        document.setIssueDate(issueDate);
        document.setExpiryDate(expiryDate);

        document.setStatus(
                VerificationDocumentStatus.SUBMITTED
        );

        Instant now = Instant.now();

        document.setUploadedAt(now);
        document.setUpdatedAt(now);

        try {

            VerificationDocument savedDocument =
                    verificationDocumentRepository.save(
                            document
                    );

            return toResponse(savedDocument);

        } catch (RuntimeException exception) {

            // Remove the Cloudinary file if database saving fails.
            try {

                verificationFileStorageService.delete(
                        storedFile.publicId()
                );

            } catch (RuntimeException cleanupException) {

                exception.addSuppressed(
                        cleanupException
                );
            }

            throw exception;
        }
    }

    /**
     * Validate whether the provider is allowed to upload
     * verification documents based on the current verification status.
     */
    private void validateDocumentUploadPermission(
            String providerId
    ) {

        ProviderVerification verification =
                providerVerificationRepository
                        .findByProviderId(providerId)
                        .orElse(null);

        // First-time verification: no existing record.
        if (verification == null) {
            return;
        }

        ProviderVerificationStatus status =
                verification.getStatus();

        if (status == null) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Verification status is unavailable. Please contact support."
            );
        }

        switch (status) {

            // Uploading is allowed.
            case NOT_SUBMITTED,
                 REJECTED,
                 RESUBMISSION_REQUESTED,
                 EXPIRED -> {
                return;
            }

            // Uploading is blocked.
            case SUBMITTED,
                 UNDER_REVIEW,
                 APPROVED,
                 SUSPENDED -> {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Verification documents cannot be uploaded while your verification status is "
                                + status
                                + ". Please wait for the verification decision."
                );
            }
        }
    }

    @Override
    public List<VerificationDocumentResponse> getMyDocuments() {

        ProviderProfile providerProfile =
                requireCurrentProviderProfile();

        return verificationDocumentRepository
                .findAllByProviderIdOrderByUploadedAtDesc(
                        providerProfile.getId()
                )
                .stream()
                .map(this::toResponse)
                .toList();
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

        if (
                issueDate != null
                && expiryDate != null
                && expiryDate.isBefore(issueDate)
        ) {

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
