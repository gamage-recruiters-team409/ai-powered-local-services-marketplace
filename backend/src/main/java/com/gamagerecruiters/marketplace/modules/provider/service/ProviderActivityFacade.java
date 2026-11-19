
package com.gamagerecruiters.marketplace.modules.provider.service;

/**
 * Read-only provider activity eligibility contract
 * for other marketplace modules.
 *
 * The providerId represents the provider USER ID,
 * not the ProviderProfile MongoDB document ID.
 */
public interface ProviderActivityFacade {

    /**
     * Checks whether a provider can receive new bookings.
     *
     * Returns false when:
     * - Provider profile does not exist.
     * - Provider is voluntarily PAUSED.
     * - Provider verification is not APPROVED.
     *
     * Existing confirmed bookings are not affected.
     */
    boolean canReceiveNewBookings(String providerId);

    /**
     * Checks whether the provider has voluntarily paused
     * their profile.
     */
    boolean isProviderPaused(String providerId);
}
