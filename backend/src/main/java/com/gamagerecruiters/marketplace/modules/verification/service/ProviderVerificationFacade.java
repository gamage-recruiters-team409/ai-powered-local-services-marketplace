
package com.gamagerecruiters.marketplace.modules.verification.service;

/**
 * Read-only facade for other modules to check provider verification eligibility.
 *
 * The providerId parameter represents the provider's USER ID,
 * not the ProviderProfile document ID.
 */
public interface ProviderVerificationFacade {

    boolean isApprovedProvider(String providerId);
}
