package com.gamagerecruiters.marketplace.modules.availability.service;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;

public interface AvailabilityService {

    AvailabilityResponse getAvailability(String providerId);

    AvailabilityResponse updateAvailability(String providerId, UpdateAvailabilityRequest request);

    List<BlockedDateResponse> getBlockedDates(String providerId, String from, String to);

    BlockedDateResponse createBlockedDate(String providerId, CreateBlockedDateRequest request);

    void deleteBlockedDate(String providerId, String blockedDateId);
}
