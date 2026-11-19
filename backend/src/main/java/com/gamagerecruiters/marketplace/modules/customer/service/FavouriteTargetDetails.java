package com.gamagerecruiters.marketplace.modules.customer.service;

import java.math.BigDecimal;

/**
 * Standardized lookup details returned by target lookup ports (FR-CUST-005).
 */
public record FavouriteTargetDetails(
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
        boolean inactive
) {}
