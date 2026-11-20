package com.gamagerecruiters.marketplace.modules.listing.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Decoupled port for looking up catalog categories without injecting foreign repositories.
 * Wired by an adapter once the catalog module is merged into {@code develop}.
 */
public interface CategoryLookupPort {

    Optional<CategorySnapshot> findById(String categoryId);

    default List<CategorySnapshot> findActiveCategories() {
        return List.of();
    }

    record CategorySnapshot(
            String id,
            String name,
            String parentId,
            boolean active,
            BigDecimal commissionRate
    ) {
    }
}
