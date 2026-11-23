package com.gamagerecruiters.marketplace.modules.provider.service;

import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderPayoutDetailsResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderPayoutDetailsRequest;

public interface ProviderPayoutDetailsService {

    /**
     * Returns the authenticated provider's payout details with a masked
     * account number.
     */
    ProviderPayoutDetailsResponse getMyPayoutDetails();

    /**
     * Creates or replaces the authenticated provider's payout details.
     */
    ProviderPayoutDetailsResponse updateMyPayoutDetails(
            UpdateProviderPayoutDetailsRequest request
    );

    /**
     * Internal read-only contract for WP07 payout processing.
     *
     * The providerUserId is the provider's USER ID, not the profile document ID.
     * Returns empty when the provider or complete payout details do not exist.
     *
     * The returned object contains the full account number. It must only be
     * consumed by trusted backend code and must never be exposed through REST.
     */
    Optional<ProviderPayoutDetails> getPayoutDetailsForPayoutProcessing(
            String providerUserId
    );
}
