package com.gamagerecruiters.marketplace.modules.customer.service;

import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouritePageResponse;

/**
 * Service managing customer favourites for providers and service listings (FR-CUST-005).
 */
public interface CustomerFavouriteService {

    /**
     * Retrieves paginated favourites for the customer and given type.
     * Targets that no longer exist are silently omitted from the returned content.
     *
     * @param customerId ID of the authenticated customer
     * @param type       PROVIDER or LISTING
     * @param page       zero-based page number
     * @param size       page size (default 6, max 50)
     * @return paginated favourites response
     */
    CustomerFavouritePageResponse getFavourites(String customerId, FavouriteType type, int page, int size);

    /**
     * Idempotently adds a favourite target. If already saved, succeeds without creating a duplicate.
     *
     * @param customerId ID of the authenticated customer
     * @param type       PROVIDER or LISTING
     * @param targetId   ID of the target provider or listing
     */
    void addFavourite(String customerId, FavouriteType type, String targetId);

    /**
     * Idempotently removes a favourite target. Returns successfully even if target was not saved.
     *
     * @param customerId ID of the authenticated customer
     * @param type       PROVIDER or LISTING
     * @param targetId   ID of the target provider or listing
     */
    void removeFavourite(String customerId, FavouriteType type, String targetId);
}
