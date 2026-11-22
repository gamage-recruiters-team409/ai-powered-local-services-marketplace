package com.gamagerecruiters.marketplace.modules.provider.service;

import java.util.Collections;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@Service
public class ProviderActivityFacadeImpl
        implements ProviderActivityFacade {

    private final ProviderProfileRepository providerProfileRepository;

    private final ProviderVerificationFacade providerVerificationFacade;

    private final DistrictCatalog districtCatalog;

    @Autowired
    public ProviderActivityFacadeImpl(
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationFacade providerVerificationFacade,
            DistrictCatalog districtCatalog
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.providerVerificationFacade = providerVerificationFacade;
        this.districtCatalog = districtCatalog;
    }

    /**
     * Compatibility constructor for existing unit tests or direct callers.
     * Spring uses the three-argument constructor above to inject the catalog.
     */
    public ProviderActivityFacadeImpl(
            ProviderProfileRepository providerProfileRepository,
            ProviderVerificationFacade providerVerificationFacade
    ) {
        this(providerProfileRepository, providerVerificationFacade, null);
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
     * Read-only WP05 contract using the provider USER ID.
     * Uses the identical resolution rule as ProviderProfileServiceImpl.
     */
    @Override
    public Set<String> getServiceAreaDistricts(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            return Collections.emptySet();
        }

        return providerProfileRepository
                .findByUserId(providerId.trim())
                .map(profile -> ProviderServiceAreaResolver.resolveDistrictSet(
                        profile,
                        districtCatalog
                ))
                .orElseGet(Collections::emptySet);
    }
}
