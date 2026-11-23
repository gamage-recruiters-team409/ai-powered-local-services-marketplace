package com.gamagerecruiters.marketplace.modules.search.dto;

public record SearchSupportedFlags(
        boolean distance,
        boolean availability,
        boolean backgroundCheck,
        boolean serviceTypes
) {
    public static final SearchSupportedFlags NONE = new SearchSupportedFlags(false, false, false, false);
}
