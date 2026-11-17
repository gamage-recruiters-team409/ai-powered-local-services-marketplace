package com.gamagerecruiters.marketplace.modules.verification.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;

public record AdminVerificationDetailResponse(
        String providerId,
        String verificationId,
        String displayName,
        ProviderType providerType,
        String district,
        String city,
        String bio,
        Integer experienceYears,
        ProviderVerificationStatus status,
        Instant submittedAt,
        Instant reviewedAt,
        String decisionReason,
        List<VerificationDocumentResponse> documents
) {
}