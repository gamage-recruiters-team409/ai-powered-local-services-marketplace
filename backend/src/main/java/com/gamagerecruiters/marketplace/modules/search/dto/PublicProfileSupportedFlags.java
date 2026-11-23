package com.gamagerecruiters.marketplace.modules.search.dto;

public record PublicProfileSupportedFlags(
        boolean distance,
        boolean availability,
        boolean reviewContent,
        boolean backgroundCheck
) {
    public static final PublicProfileSupportedFlags NONE =
            new PublicProfileSupportedFlags(false, false, false, false);
}
