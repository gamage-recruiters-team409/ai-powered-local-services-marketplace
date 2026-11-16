package com.gamagerecruiters.marketplace.modules.verification.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "provider_verifications")
public class ProviderVerification {

    @Id
    private String id;

    @Indexed(unique = true)
    private String providerId;

    private ProviderVerificationStatus status;

    private Instant submittedAt;

    private Instant reviewedAt;

    private String reviewedByUserId;

    private String decisionReason;

    private Instant createdAt;

    private Instant updatedAt;

    // Exact document IDs included in the current submission.
    private List<String> submittedDocumentIds;

    // Incremented whenever an upload reservation or workflow transition occurs.
    // Used as a compare-and-set value when submitting a verification.
    private long coordinationRevision;

    // Number of uploads that have reserved a slot but have not finished persistence.
    private int inFlightUploadCount;

    public ProviderVerification() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public ProviderVerificationStatus getStatus() {
        return status;
    }

    public void setStatus(ProviderVerificationStatus status) {
        this.status = status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewedByUserId() {
        return reviewedByUserId;
    }

    public void setReviewedByUserId(String reviewedByUserId) {
        this.reviewedByUserId = reviewedByUserId;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<String> getSubmittedDocumentIds() {
        return submittedDocumentIds;
    }

    public void setSubmittedDocumentIds(List<String> submittedDocumentIds) {
        this.submittedDocumentIds = submittedDocumentIds;
    }

    public long getCoordinationRevision() {
        return coordinationRevision;
    }

    public void setCoordinationRevision(long coordinationRevision) {
        this.coordinationRevision = coordinationRevision;
    }

    public int getInFlightUploadCount() {
        return inFlightUploadCount;
    }

    public void setInFlightUploadCount(int inFlightUploadCount) {
        this.inFlightUploadCount = inFlightUploadCount;
    }
}
