
package com.gamagerecruiters.marketplace.modules.verification.service;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

@Service
public class ProviderVerificationFacadeImpl
        implements ProviderVerificationFacade {

    private final ProviderProfileRepository providerProfileRepository;
    private final ProviderVerificationRepository providerVerificationRepository;

    public ProviderVerificationFacadeImpl(
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationRepository providerVerificationRepository
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.providerVerificationRepository = providerVerificationRepository;
    }

    /**
     * Checks whether a provider is APPROVED.
     *
     * @param providerId provider's USER ID
     * @return true only when the provider has an APPROVED verification record;
     *         false when the provider or verification record does not exist,
     *         or when the verification status is anything other than APPROVED.
     */
    @Override
    public boolean isApprovedProvider(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            return false;
        }

        return providerProfileRepository.findByUserId(providerId.trim())

                // Resolve the provider's profile ID from their user ID.
                .map(ProviderProfile::getId)

                // Find the verification record using the profile ID.
                .filter(profileId -> !profileId.isBlank())
                .flatMap(providerVerificationRepository::findByProviderId)

                // Only APPROVED is eligible.
                .map(verification ->
                        verification.getStatus() == ProviderVerificationStatus.APPROVED
                )

                // Unknown provider or missing verification record.
                .orElse(false);
    }
}
