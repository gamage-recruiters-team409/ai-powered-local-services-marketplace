package com.gamagerecruiters.marketplace.modules.provider.service;

import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;

public interface ProviderProfileService {

    ProviderProfileResponse createMyProfile(
            CreateProviderProfileRequest request
    );

    ProviderProfileResponse getMyProfile();

    ProviderProfileResponse updateMyProfile(
            UpdateProviderProfileRequest request
    );

    ProviderProfileResponse uploadMyProfileImage(
            MultipartFile file
    );

    ProviderProfileResponse removeMyProfileImage();

    /**
     * Returns the configured service-area districts for a provider.
     *
     * @param providerId the provider's user ID (not the profile document ID)
     * @return configured district names, or an empty set when none are configured
     */
    Set<String> getServiceAreaDistricts(String providerId);
}
