package com.gamagerecruiters.marketplace.modules.verification.service;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationDetailResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationQueueItemResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;

public interface VerificationAdminService {

    List<AdminVerificationQueueItemResponse> getPendingVerifications();

    AdminVerificationDetailResponse getVerificationDetails(String providerId);

    ProviderVerificationResponse startReview(String providerId);

    ProviderVerificationResponse approveVerification(String providerId);

    ProviderVerificationResponse rejectVerification(
            String providerId,
            String reason
    );

    ProviderVerificationResponse requestResubmission(
            String providerId,
            String reason
    );
}