package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderActivityFacade;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

/**
 * ProviderProfilePort: verification through ProviderVerificationFacade.isApprovedProvider and the service area
 * through ProviderActivityFacade.getServiceAreaDistricts (both read-only, by provider user id). PRO answers empty:
 * there is no PRO / subscription flag in the provider module yet.
 */
@Component
public class ProviderVerificationProfileAdapter implements ProviderProfilePort {

    private final ProviderVerificationFacade verificationFacade;
    private final ProviderActivityFacade activityFacade;

    public ProviderVerificationProfileAdapter(ProviderVerificationFacade verificationFacade,
                                              ProviderActivityFacade activityFacade) {
        this.verificationFacade = verificationFacade;
        this.activityFacade = activityFacade;
    }

    @Override
    public Optional<Boolean> verified(String providerUserId) {
        return Optional.of(verificationFacade.isApprovedProvider(providerUserId));
    }

    @Override
    public Optional<ServiceArea> serviceArea(String providerUserId) {
        Set<String> districts = activityFacade.getServiceAreaDistricts(providerUserId);
        return Optional.of(new ServiceArea(districts == null ? java.util.List.of()
                : districts.stream().sorted().toList()));
    }

    @Override
    public Optional<Boolean> pro(String providerUserId) {
        return Optional.empty();
    }
}
