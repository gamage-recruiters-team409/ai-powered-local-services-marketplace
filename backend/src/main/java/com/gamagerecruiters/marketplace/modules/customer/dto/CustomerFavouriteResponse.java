package com.gamagerecruiters.marketplace.modules.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;

/**
 * Standardized customer favourite response item (FR-CUST-005).
 * Unused fields for a given type are null.
 */
public record CustomerFavouriteResponse(
        String favouriteId,
        FavouriteType type,
        String targetId,
        String name,
        String subtitle,
        String category,
        String city,
        String imageUrl,
        Double rating,
        Integer reviewCount,
        BigDecimal priceFrom,
        String priceUnit,
        String currency,
        boolean inactive,
        Instant savedAt
) {}
