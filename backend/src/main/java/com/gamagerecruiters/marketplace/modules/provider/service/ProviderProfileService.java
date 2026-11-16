package com.gamagerecruiters.marketplace.modules.provider.service;

import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;

public interface ProviderProfileService {

    ProviderProfileResponse createMyProfile(CreateProviderProfileRequest request);

    ProviderProfileResponse getMyProfile();

    ProviderProfileResponse updateMyProfile(UpdateProviderProfileRequest request);
}