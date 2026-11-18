package com.gamagerecruiters.marketplace.modules.customer.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Port for looking up provider details for favourites (FR-CUST-005).
 */
public interface FavouriteProviderLookup {

    /**
     * Resolves details for a single provider target ID.
     *
     * @param targetId provider profile ID or provider user ID
     * @return details if found, empty otherwise
     */
    Optional<FavouriteTargetDetails> lookup(String targetId);

    /**
     * Batch-resolves details for a collection of provider target IDs without N+1 queries.
     *
     * @param targetIds provider profile IDs or provider user IDs
     * @return map keyed by each matching target ID
     */
    Map<String, FavouriteTargetDetails> lookupBatch(Collection<String> targetIds);
}
