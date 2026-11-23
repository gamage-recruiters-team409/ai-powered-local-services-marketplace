
package com.gamagerecruiters.marketplace.modules.provider.service;

import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileActivityResponse;

public interface ProviderProfileActivityService {

    ProviderProfileActivityResponse getMyActivity();

    ProviderProfileActivityResponse pauseMyProfile();

    ProviderProfileActivityResponse reactivateMyProfile();
}
