package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

/**
 * ProviderProfilePort: verification through ProviderVerificationFacade.isApprovedProvider (user id). Service area
 * and PRO answer empty: ProviderProfileService only reads the logged-in provider's own profile and there is no
 * PRO / subscription concept yet (provider WP dependency).
 */
@Component
public class ProviderVerificationProfileAdapter implements ProviderProfilePort {

    private final ProviderVerificationFacade verificationFacade;

    public ProviderVerificationProfileAdapter(ProviderVerificationFacade verificationFacade) {
        this.verificationFacade = verificationFacade;
    }

    @Override
    public Optional<Boolean> verified(String providerUserId) {
        return Optional.of(verificationFacade.isApprovedProvider(providerUserId));
    }

    @Override
    public Optional<ServiceArea> serviceArea(String providerUserId) {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> pro(String providerUserId) {
        return Optional.empty();
    }
}
