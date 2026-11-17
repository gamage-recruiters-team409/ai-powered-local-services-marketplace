package com.gamagerecruiters.marketplace.modules.verification.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;

public record AdminVerificationQueueItemResponse(
        String providerId,
        String verificationId,
        String displayName,
        ProviderType providerType,
        String district,
        String city,
        ProviderVerificationStatus status,
        Instant submittedAt,
        long documentCount
) {
}