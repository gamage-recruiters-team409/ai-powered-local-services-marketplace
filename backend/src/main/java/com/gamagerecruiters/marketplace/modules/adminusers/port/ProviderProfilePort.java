package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.util.List;
import java.util.Optional;

/**
 * Provider profile facts (provider + verification modules). The real adapter answers {@link #verified} through
 * ProviderVerificationFacade and {@link #serviceArea} through ProviderActivityFacade.getServiceAreaDistricts.
 * PRO answers empty: the provider module has no PRO / subscription flag yet.
 */
public interface ProviderProfilePort {

    /** Districts the provider serves, sorted; empty when none are configured. */
    record ServiceArea(List<String> districts) {
    }

    Optional<Boolean> verified(String providerUserId);

    Optional<ServiceArea> serviceArea(String providerUserId);

    Optional<Boolean> pro(String providerUserId);
}
