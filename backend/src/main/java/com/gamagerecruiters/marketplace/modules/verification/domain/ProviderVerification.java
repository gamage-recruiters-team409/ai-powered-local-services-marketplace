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

    // Exact document IDs included in the current submission.
    private List<String> submittedDocumentIds;

    // Incremented whenever an upload reservation or workflow transition occurs.
    // Used as a compare-and-set value when submitting a verification.
    private long coordinationRevision;

    // Kept for compatibility with the existing atomic submission/review checks.
    // It must always equal the number of active uploadReservations.
    private int inFlightUploadCount;

    // Each upload has its own identity and lease. This allows safe release and
    // recovery without accidentally releasing another upload's reservation.
    private List<UploadReservation> uploadReservations = new ArrayList<>();

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

    public List<UploadReservation> getUploadReservations() {
        return uploadReservations;
    }

    public void setUploadReservations(List<UploadReservation> uploadReservations) {
        this.uploadReservations = uploadReservations == null
                ? new ArrayList<>()
                : uploadReservations;
    }

    public static class UploadReservation {

        private String reservationId;

        private Instant startedAt;

        private Instant leaseExpiresAt;

        public UploadReservation() {
        }

        public UploadReservation(
                String reservationId,
                Instant startedAt,
                Instant leaseExpiresAt
        ) {
            this.reservationId = reservationId;
            this.startedAt = startedAt;
            this.leaseExpiresAt = leaseExpiresAt;
        }

        public String getReservationId() {
            return reservationId;
        }

        public void setReservationId(String reservationId) {
            this.reservationId = reservationId;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }

        public Instant getLeaseExpiresAt() {
            return leaseExpiresAt;
        }

        public void setLeaseExpiresAt(Instant leaseExpiresAt) {
            this.leaseExpiresAt = leaseExpiresAt;
        }
    }
}
