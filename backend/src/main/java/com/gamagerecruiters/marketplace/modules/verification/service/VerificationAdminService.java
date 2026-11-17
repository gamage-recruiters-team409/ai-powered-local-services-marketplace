package com.gamagerecruiters.marketplace.modules.verification.service;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationDetailResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationQueueItemResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentAccessResponse;

public interface VerificationAdminService {

    List<AdminVerificationQueueItemResponse> getPendingVerifications();

    AdminVerificationDetailResponse getVerificationDetails(
            String providerId
    );

    VerificationDocumentAccessResponse getVerificationDocumentAccess(
            String providerId,
            String documentId
    );

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