package com.gamagerecruiters.marketplace.modules.verification.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;

public class VerificationDocumentResponse {

    private final String id;
    private final VerificationDocumentType documentType;
    private final String originalFileName;
    private final String format;
    private final long bytes;
    private final LocalDate issueDate;
    private final LocalDate expiryDate;
    private final VerificationDocumentStatus status;
    private final Instant uploadedAt;

    public VerificationDocumentResponse(
            String id,
            VerificationDocumentType documentType,
            String originalFileName,
            String format,
            long bytes,
            LocalDate issueDate,
            LocalDate expiryDate,
            VerificationDocumentStatus status,
            Instant uploadedAt
    ) {
        this.id = id;
        this.documentType = documentType;
        this.originalFileName = originalFileName;
        this.format = format;
        this.bytes = bytes;
        this.issueDate = issueDate;
        this.expiryDate = expiryDate;
        this.status = status;
        this.uploadedAt = uploadedAt;
    }

    public String getId() {
        return id;
    }

    public VerificationDocumentType getDocumentType() {
        return documentType;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getFormat() {
        return format;
    }

    public long getBytes() {
        return bytes;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public VerificationDocumentStatus getStatus() {
        return status;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}