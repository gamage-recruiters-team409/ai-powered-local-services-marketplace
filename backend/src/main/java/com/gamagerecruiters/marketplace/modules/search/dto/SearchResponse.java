package com.gamagerecruiters.marketplace.modules.search.dto;

import java.util.List;

public record SearchResponse(
        List<ProviderSearchResult> results,
        int page,
        int size,
        long totalCount,
        List<String> unsupportedFilters
) {
    public SearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
        unsupportedFilters = unsupportedFilters == null ? List.of() : List.copyOf(unsupportedFilters);
    }
}
