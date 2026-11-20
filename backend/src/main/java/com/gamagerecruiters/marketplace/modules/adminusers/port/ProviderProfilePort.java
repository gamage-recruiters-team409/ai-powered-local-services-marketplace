package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.util.Optional;

/**
 * Provider profile facts (provider + verification modules). The real adapter answers {@link #verified} through
 * ProviderVerificationFacade. Service area and PRO have no cross-module method (ProviderProfileService only reads
 * the logged-in provider's own profile; there is no PRO / subscription concept yet), so they answer empty.
 */
public interface ProviderProfilePort {

    record ServiceArea(String district, String city) {
    }

    Optional<Boolean> verified(String providerUserId);

    Optional<ServiceArea> serviceArea(String providerUserId);

    Optional<Boolean> pro(String providerUserId);
}
