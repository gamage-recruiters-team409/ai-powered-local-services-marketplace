package com.gamagerecruiters.marketplace.modules.search.dto;

import java.math.BigDecimal;
import java.util.Set;

public record SearchRequest(
        String q,
        String location,
        String category,
        BigDecimal maxPrice,
        boolean verified,
        String sort,
        int page,
        int size,
        Set<String> presentParams
) {
    public SearchRequest {
        presentParams = presentParams == null ? Set.of() : Set.copyOf(presentParams);
    }
}
