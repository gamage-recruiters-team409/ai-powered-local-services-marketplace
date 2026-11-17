
package com.gamagerecruiters.marketplace.modules.verification.service;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationFacadeImplTest {

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private ProviderVerificationRepository providerVerificationRepository;

    @InjectMocks
    private ProviderVerificationFacadeImpl facade;

    private static final String USER_ID = "user-123";
    private static final String PROFILE_ID = "profile-456";

    private ProviderProfile createProviderProfile() {
        ProviderProfile profile = mock(ProviderProfile.class);
        when(profile.getId()).thenReturn(PROFILE_ID);
        return profile;
    }

    @Test
    void returnsTrueWhenProviderIsApproved() {

        ProviderProfile profile = createProviderProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        ProviderVerification verification = new ProviderVerification();
        verification.setStatus(ProviderVerificationStatus.APPROVED);

        when(providerVerificationRepository.findByProviderId(PROFILE_ID))
                .thenReturn(Optional.of(verification));

        assertTrue(facade.isApprovedProvider(USER_ID));
    }

    @Test
    void returnsFalseWhenProviderDoesNotExist() {

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertFalse(facade.isApprovedProvider(USER_ID));

        verifyNoInteractions(providerVerificationRepository);
    }

    @Test
    void returnsFalseWhenVerificationRecordDoesNotExist() {

        ProviderProfile profile = createProviderProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        when(providerVerificationRepository.findByProviderId(PROFILE_ID))
                .thenReturn(Optional.empty());

        assertFalse(facade.isApprovedProvider(USER_ID));
    }

    @Test
    void returnsFalseForEveryNonApprovedStatus() {

        ProviderProfile profile = createProviderProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        for (ProviderVerificationStatus status :
                ProviderVerificationStatus.values()) {

            if (status == ProviderVerificationStatus.APPROVED) {
                continue;
            }

            ProviderVerification verification = new ProviderVerification();
            verification.setStatus(status);

            when(providerVerificationRepository.findByProviderId(PROFILE_ID))
                    .thenReturn(Optional.of(verification));

            assertFalse(
                    facade.isApprovedProvider(USER_ID),
                    "Expected false for status: " + status
            );
        }
    }

    @Test
    void returnsFalseWhenVerificationStatusIsNull() {

        ProviderProfile profile = createProviderProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        ProviderVerification verification = new ProviderVerification();
        verification.setStatus(null);

        when(providerVerificationRepository.findByProviderId(PROFILE_ID))
                .thenReturn(Optional.of(verification));

        assertFalse(facade.isApprovedProvider(USER_ID));
    }

    @Test
    void returnsFalseWhenUserIdIsNullOrBlank() {

        assertFalse(facade.isApprovedProvider(null));
        assertFalse(facade.isApprovedProvider(""));
        assertFalse(facade.isApprovedProvider("   "));

        verifyNoInteractions(
                providerProfileRepository,
                providerVerificationRepository
        );
    }
}
