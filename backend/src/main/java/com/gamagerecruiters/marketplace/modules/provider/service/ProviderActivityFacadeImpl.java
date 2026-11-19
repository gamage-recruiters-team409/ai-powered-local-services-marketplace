
package com.gamagerecruiters.marketplace.modules.provider.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
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

    /**
     * Returns the configured service-area districts
     * using the provider USER ID.
     *
     * This method is read-only and does not modify
     * the provider profile.
     */
    @Override
    public Set<String> getServiceAreaDistricts(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            return Collections.emptySet();
        }

        return providerProfileRepository
                .findByUserId(providerId.trim())
                .map(this::extractServiceAreaDistricts)
                .orElseGet(Collections::emptySet);
    }

    /**
     * Creates an immutable set containing the configured
     * district names.
     */
    private Set<String> extractServiceAreaDistricts(
            ProviderProfile profile
    ) {

        Set<String> districts = new LinkedHashSet<>();

        profile.getServiceAreaDistricts()
                .stream()
                .filter(district ->
                        district != null && !district.isBlank()
                )
                .map(String::trim)
                .forEach(districts::add);

        return Collections.unmodifiableSet(districts);
    }
}
