package com.gamagerecruiters.marketplace.modules.verification.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationReverificationServiceImplTest {

    private static final String PROVIDER_ID = "profile-1";

    @Mock
    private VerificationAtomicUpdates verificationAtomicUpdates;

    private ProviderVerificationReverificationServiceImpl service;

    @BeforeEach
    void setUp() {

        service = new ProviderVerificationReverificationServiceImpl(
                verificationAtomicUpdates
        );
    }

    @Test
    void requestCategoryReverification_shouldDelegateToAtomicUpdates() {

        service.requestCategoryReverification(PROVIDER_ID);

        verify(verificationAtomicUpdates)
                .requestCategoryReverification(
                        eq(PROVIDER_ID),
                        any(Instant.class)
                );
    }
}