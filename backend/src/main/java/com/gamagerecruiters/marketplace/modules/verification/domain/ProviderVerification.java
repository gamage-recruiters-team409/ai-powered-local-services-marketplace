package com.gamagerecruiters.marketplace.modules.verification.domain;

import java.time.Instant;
import java.util.ArrayList;
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
    private List<String> submittedDocumentIds;
    private long coordinationRevision;
    private int inFlightUploadCount;
    private List<UploadReservation> uploadReservations = new ArrayList<>();

    // Verification expiry tracking.
    private Instant expiredAt;
    private Instant gracePeriodEndsAt;
    private List<String> expiredDocumentIds = new ArrayList<>();

    // Persisted expiry-event delivery state. Claim is a renewable-by-time lease.
    private boolean expiryEventPending;
    private Instant expiryEventLeaseUntil;
    private String expiryEventClaimToken;
    private Instant expiryEventPublishedAt;

    public ProviderVerification() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public ProviderVerificationStatus getStatus() { return status; }
    public void setStatus(ProviderVerificationStatus status) { this.status = status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(String reviewedByUserId) { this.reviewedByUserId = reviewedByUserId; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<String> getSubmittedDocumentIds() { return submittedDocumentIds; }
    public void setSubmittedDocumentIds(List<String> submittedDocumentIds) {
        this.submittedDocumentIds = submittedDocumentIds == null
                ? null : new ArrayList<>(submittedDocumentIds);
    }

    public long getCoordinationRevision() { return coordinationRevision; }
    public void setCoordinationRevision(long coordinationRevision) {
        this.coordinationRevision = coordinationRevision;
    }
    public int getInFlightUploadCount() { return inFlightUploadCount; }
    public void setInFlightUploadCount(int inFlightUploadCount) {
        this.inFlightUploadCount = inFlightUploadCount;
    }
    public List<UploadReservation> getUploadReservations() { return uploadReservations; }
    public void setUploadReservations(List<UploadReservation> uploadReservations) {
        this.uploadReservations = uploadReservations == null
                ? new ArrayList<>() : new ArrayList<>(uploadReservations);
    }

    public Instant getExpiredAt() { return expiredAt; }
    public void setExpiredAt(Instant expiredAt) { this.expiredAt = expiredAt; }
    public Instant getGracePeriodEndsAt() { return gracePeriodEndsAt; }
    public void setGracePeriodEndsAt(Instant gracePeriodEndsAt) {
        this.gracePeriodEndsAt = gracePeriodEndsAt;
    }
    public List<String> getExpiredDocumentIds() {
        return expiredDocumentIds == null ? List.of() : List.copyOf(expiredDocumentIds);
    }
    public void setExpiredDocumentIds(List<String> expiredDocumentIds) {
        this.expiredDocumentIds = expiredDocumentIds == null
                ? new ArrayList<>() : new ArrayList<>(expiredDocumentIds);
    }

    public boolean isExpiryEventPending() { return expiryEventPending; }
    public void setExpiryEventPending(boolean expiryEventPending) {
        this.expiryEventPending = expiryEventPending;
    }
    public Instant getExpiryEventLeaseUntil() { return expiryEventLeaseUntil; }
    public void setExpiryEventLeaseUntil(Instant expiryEventLeaseUntil) {
        this.expiryEventLeaseUntil = expiryEventLeaseUntil;
    }
    public String getExpiryEventClaimToken() { return expiryEventClaimToken; }
    public void setExpiryEventClaimToken(String expiryEventClaimToken) {
        this.expiryEventClaimToken = expiryEventClaimToken;
    }
    public Instant getExpiryEventPublishedAt() { return expiryEventPublishedAt; }
    public void setExpiryEventPublishedAt(Instant expiryEventPublishedAt) {
        this.expiryEventPublishedAt = expiryEventPublishedAt;
    }

    public static class UploadReservation {
        private String reservationId;
        private Instant startedAt;
        private Instant leaseExpiresAt;

        public UploadReservation() {
        }
        public UploadReservation(String reservationId, Instant startedAt, Instant leaseExpiresAt) {
            this.reservationId = reservationId;
            this.startedAt = startedAt;
            this.leaseExpiresAt = leaseExpiresAt;
        }
        public String getReservationId() { return reservationId; }
        public void setReservationId(String reservationId) { this.reservationId = reservationId; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
        public void setLeaseExpiresAt(Instant leaseExpiresAt) {
            this.leaseExpiresAt = leaseExpiresAt;
        }
    }
}
