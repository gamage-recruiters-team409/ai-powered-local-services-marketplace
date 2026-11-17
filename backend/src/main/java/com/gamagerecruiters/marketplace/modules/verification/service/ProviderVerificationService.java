package com.gamagerecruiters.marketplace.modules.verification.service;

import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;

public interface ProviderVerificationService {

    ProviderVerificationResponse submitMyVerification();

    ProviderVerificationResponse getMyVerificationStatus();
}