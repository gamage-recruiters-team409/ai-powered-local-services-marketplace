package com.gamagerecruiters.marketplace.modules.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;
import com.gamagerecruiters.marketplace.modules.availability.repository.BlockedDateRepository;

@ExtendWith(MockitoExtension.class)
class AvailabilityFacadeImplTest {

    @Mock
    private AvailabilityService availabilityService;

    @Mock
    private BlockedDateRepository blockedDateRepository;

    private AvailabilityFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new AvailabilityFacadeImpl(availabilityService, blockedDateRepository);
    }

    @Test
    @DisplayName("getSchedule returns weekly hours and buffer from the availability service")
    void getSchedule() {
        List<WeeklyHoursResponse> hours = List.of(new WeeklyHoursResponse(DayOfWeek.MONDAY, true, "09:00", "17:00"));
        when(availabilityService.getAvailability("p-1")).thenReturn(new AvailabilityResponse("p-1", hours, 45, null));

        assertThat(facade.getSchedule("p-1")).isEqualTo(new ProviderSchedule("p-1", hours, 45));
    }

    @Test
    @DisplayName("isDateBlocked checks the provider's blocked date as yyyy-MM-dd")
    void isDateBlocked() {
        when(blockedDateRepository.existsByProviderIdAndDate("p-1", "2026-11-20")).thenReturn(true);

        assertThat(facade.isDateBlocked("p-1", LocalDate.parse("2026-11-20"))).isTrue();
    }
}
