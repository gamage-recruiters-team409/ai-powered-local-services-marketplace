package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
public class ProviderVerificationReverificationServiceImpl
        implements ProviderVerificationReverificationService {

    private final VerificationAtomicUpdates verificationAtomicUpdates;

    public ProviderVerificationReverificationServiceImpl(
            VerificationAtomicUpdates verificationAtomicUpdates
    ) {
        this.verificationAtomicUpdates = verificationAtomicUpdates;
    }

    @Override
    public void requestCategoryReverification(String providerId) {

        verificationAtomicUpdates.requestCategoryReverification(
                providerId,
                Instant.now()
        );
    }
}