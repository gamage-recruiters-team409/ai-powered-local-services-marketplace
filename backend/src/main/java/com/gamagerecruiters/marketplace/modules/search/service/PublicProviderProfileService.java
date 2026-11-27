package com.gamagerecruiters.marketplace.modules.search.service;

import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderProfileResponse;

public interface PublicProviderProfileService {

    PublicProviderProfileResponse getPublicProfile(String providerIdOrProfileId);

    PublicProviderProfileResponse getPublicProfile(String providerIdOrProfileId, int reviewPage);
}
