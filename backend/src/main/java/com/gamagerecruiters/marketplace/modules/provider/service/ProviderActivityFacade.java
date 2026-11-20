
package com.gamagerecruiters.marketplace.modules.provider.service;

import java.util.Set;

/**
 * Read-only provider activity and service-area contract
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

    /**
     * Returns the provider's configured service-area districts.
     *
     * @param providerId provider USER ID
     * @return immutable set of district names, or an empty set
     *         when the provider has no configured service area
     */
    Set<String> getServiceAreaDistricts(String providerId);
}
