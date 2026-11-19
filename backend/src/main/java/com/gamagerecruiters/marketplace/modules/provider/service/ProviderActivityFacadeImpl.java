
package com.gamagerecruiters.marketplace.modules.provider.service;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@Service
public class ProviderActivityFacadeImpl
        implements ProviderActivityFacade {

    private final ProviderProfileRepository providerProfileRepository;

    private final ProviderVerificationFacade providerVerificationFacade;

    public ProviderActivityFacadeImpl(
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationFacade providerVerificationFacade
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.providerVerificationFacade = providerVerificationFacade;
    }

    @Override
    public boolean canReceiveNewBookings(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            return false;
        }

        String normalizedProviderId = providerId.trim();

        // Verification must be approved first.
        if (!providerVerificationFacade.isApprovedProvider(
                normalizedProviderId
        )) {
            return false;
        }

        // Check the latest voluntary activity status.
        return providerProfileRepository
                .findByUserId(normalizedProviderId)
                .map(profile ->
                        profile.getActivityStatus()
                                == ProviderProfileActivityStatus.ACTIVE
                )
                .orElse(false);
    }

    @Override
    public boolean isProviderPaused(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            return false;
        }

        return providerProfileRepository
                .findByUserId(providerId.trim())
                .map(profile ->
                        profile.getActivityStatus()
                                == ProviderProfileActivityStatus.PAUSED
                )
                .orElse(false);
    }
}
