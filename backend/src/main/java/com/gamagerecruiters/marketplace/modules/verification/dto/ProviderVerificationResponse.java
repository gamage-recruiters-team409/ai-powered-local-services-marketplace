package com.gamagerecruiters.marketplace.modules.verification.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;

public class ProviderVerificationResponse {

    private final String id;

    private final ProviderVerificationStatus status;

    private final Instant submittedAt;

    private final Instant reviewedAt;

    private final String decisionReason;

    private final Instant updatedAt;

    public ProviderVerificationResponse(
            String id,
            ProviderVerificationStatus status,
            Instant submittedAt,
            Instant reviewedAt,
            String decisionReason,
            Instant updatedAt
    ) {
        this.id = id;
        this.status = status;
        this.submittedAt = submittedAt;
        this.reviewedAt = reviewedAt;
        this.decisionReason = decisionReason;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public ProviderVerificationStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}