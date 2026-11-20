package com.gamagerecruiters.marketplace.modules.customer.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Port for looking up listing details for favourites (FR-CUST-005).
 * If no implementation is present (e.g. WP03 listing module not yet merged),
 * customer favourites service fails closed with 503 FAVOURITES_LISTING_UNAVAILABLE.
 */
public interface FavouriteListingLookup {

    /**
     * Resolves details for a single listing target ID.
     *
     * @param targetId listing ID
     * @return details if found, empty otherwise
     */
    Optional<FavouriteTargetDetails> lookup(String targetId);

    /**
     * Batch-resolves details for a collection of listing target IDs without N+1 queries.
     *
     * @param targetIds listing IDs
     * @return map keyed by each matching target ID
     */
    Map<String, FavouriteTargetDetails> lookupBatch(Collection<String> targetIds);
}
