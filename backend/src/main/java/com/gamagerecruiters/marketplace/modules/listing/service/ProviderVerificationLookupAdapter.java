package com.gamagerecruiters.marketplace.modules.listing.service;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

/**
 * Cross-module adapter connecting {@link ProviderVerificationPort} to WP02's read-only
 * {@link ProviderVerificationFacade} by provider user ID without mutating {@code SecurityContextHolder}
 * or injecting foreign repositories.
 */
@Component
public class ProviderVerificationLookupAdapter implements ProviderVerificationPort {

    private final ProviderVerificationFacade providerVerificationFacade;

    public ProviderVerificationLookupAdapter(ProviderVerificationFacade providerVerificationFacade) {
        this.providerVerificationFacade = providerVerificationFacade;
    }

    @Override
    public boolean isProviderVerified(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank() || providerVerificationFacade == null) {
            return false;
        }
        try {
            return providerVerificationFacade.isApprovedProvider(providerUserId.trim());
        } catch (ResourceNotFoundException ex) {
            return false;
        }
    }
}
