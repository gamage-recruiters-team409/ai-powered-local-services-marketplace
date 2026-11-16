package com.gamagerecruiters.marketplace.modules.availability.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;
import com.gamagerecruiters.marketplace.modules.availability.repository.BlockedDateRepository;

@Service
public class AvailabilityFacadeImpl implements AvailabilityFacade {

    private final AvailabilityService availabilityService;
    private final BlockedDateRepository blockedDateRepository;

    public AvailabilityFacadeImpl(AvailabilityService availabilityService,
                                  BlockedDateRepository blockedDateRepository) {
        this.availabilityService = availabilityService;
        this.blockedDateRepository = blockedDateRepository;
    }

    @Override
    public ProviderSchedule getSchedule(String providerId) {
        AvailabilityResponse availability = availabilityService.getAvailability(providerId);
        return new ProviderSchedule(availability.providerId(), availability.weeklyHours(),
                availability.bufferMinutes());
    }

    @Override
    public boolean isDateBlocked(String providerId, LocalDate date) {
        return blockedDateRepository.existsByProviderIdAndDate(providerId, date.toString());
    }
}
