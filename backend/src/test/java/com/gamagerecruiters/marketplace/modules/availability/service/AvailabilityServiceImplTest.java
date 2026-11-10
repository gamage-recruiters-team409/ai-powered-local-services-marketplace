package com.gamagerecruiters.marketplace.modules.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;
import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursRequest;
import com.gamagerecruiters.marketplace.modules.availability.repository.AvailabilityRuleRepository;
import com.gamagerecruiters.marketplace.modules.availability.repository.BlockedDateRepository;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceImplTest {

    private static final String PROVIDER_ID = "provider-1";

    // 2026-11-10 09:30 in Asia/Colombo
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), AvailabilityServiceImpl.BUSINESS_ZONE);

    @Mock
    private AvailabilityRuleRepository availabilityRuleRepository;

    @Mock
    private BlockedDateRepository blockedDateRepository;

    private AvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AvailabilityServiceImpl(availabilityRuleRepository, blockedDateRepository, FIXED_CLOCK);
    }

    private static List<WeeklyHoursRequest> fullWeek() {
        List<WeeklyHoursRequest> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            boolean weekday = day.getValue() <= 5;
            days.add(new WeeklyHoursRequest(day, weekday, weekday ? "09:00" : null, weekday ? "17:00" : null));
        }
        return days;
    }

    // ---------- weekly hours ----------

    @Test
    @DisplayName("Returns a default schedule (all days off, 30 min buffer) when the provider has none")
    void getAvailabilityReturnsDefault() {
        when(availabilityRuleRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());

        AvailabilityResponse response = service.getAvailability(PROVIDER_ID);

        assertThat(response.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(response.weeklyHours()).hasSize(7).allMatch(d -> !d.enabled());
        assertThat(response.bufferMinutes()).isEqualTo(AvailabilityServiceImpl.DEFAULT_BUFFER_MINUTES);
        verify(availabilityRuleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Saves a valid weekly schedule for the provider")
    void updateAvailabilitySavesValidSchedule() {
        when(availabilityRuleRepository.findByProviderId(PROVIDER_ID)).thenReturn(Optional.empty());
        when(availabilityRuleRepository.save(any(AvailabilityRule.class))).thenAnswer(inv -> inv.getArgument(0));

        AvailabilityResponse response =
                service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(fullWeek(), 45));

        assertThat(response.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(response.bufferMinutes()).isEqualTo(45);
        assertThat(response.weeklyHours()).hasSize(7);
        assertThat(response.weeklyHours().get(0).dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(response.updatedAt()).isEqualTo(Instant.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("Rejects a schedule where the same day appears twice")
    void updateAvailabilityRejectsDuplicateDay() {
        List<WeeklyHoursRequest> days = fullWeek();
        days.set(6, new WeeklyHoursRequest(DayOfWeek.MONDAY, false, null, null));

        assertThatThrownBy(() -> service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(days, 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("INVALID_WEEKLY_HOURS"));
        verify(availabilityRuleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects an enabled day where start time is not before end time")
    void updateAvailabilityRejectsStartAfterEnd() {
        List<WeeklyHoursRequest> days = fullWeek();
        days.set(0, new WeeklyHoursRequest(DayOfWeek.MONDAY, true, "18:00", "09:00"));

        assertThatThrownBy(() -> service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(days, 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Rejects an enabled day without times")
    void updateAvailabilityRejectsEnabledDayWithoutTimes() {
        List<WeeklyHoursRequest> days = fullWeek();
        days.set(0, new WeeklyHoursRequest(DayOfWeek.MONDAY, true, null, "17:00"));

        assertThatThrownBy(() -> service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(days, 30)))
                .isInstanceOf(ApiException.class);
    }

    // ---------- blocked dates ----------

    @Test
    @DisplayName("Creates a blocked date for today or later")
    void createBlockedDateSucceeds() {
        when(blockedDateRepository.existsByProviderIdAndDate(PROVIDER_ID, "2026-11-20")).thenReturn(false);
        when(blockedDateRepository.save(any(BlockedDate.class))).thenAnswer(inv -> {
            BlockedDate saved = inv.getArgument(0);
            saved.setId("bd-1");
            return saved;
        });

        BlockedDateResponse response = service.createBlockedDate(PROVIDER_ID,
                new CreateBlockedDateRequest("2026-11-20", BlockReason.HOLIDAY, "  Family trip "));

        assertThat(response.id()).isEqualTo("bd-1");
        assertThat(response.date()).isEqualTo("2026-11-20");
        assertThat(response.reason()).isEqualTo(BlockReason.HOLIDAY);
        assertThat(response.note()).isEqualTo("Family trip");
    }

    @Test
    @DisplayName("Rejects blocking a past date")
    void createBlockedDateRejectsPastDate() {
        assertThatThrownBy(() -> service.createBlockedDate(PROVIDER_ID,
                new CreateBlockedDateRequest("2026-11-09", BlockReason.PERSONAL, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("INVALID_BLOCKED_DATE"));
    }

    @Test
    @DisplayName("Rejects an invalid calendar date")
    void createBlockedDateRejectsInvalidDate() {
        assertThatThrownBy(() -> service.createBlockedDate(PROVIDER_ID,
                new CreateBlockedDateRequest("2026-02-30", BlockReason.PERSONAL, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("INVALID_DATE"));
    }

    @Test
    @DisplayName("Returns 409 when the date is already blocked")
    void createBlockedDateRejectsDuplicate() {
        when(blockedDateRepository.existsByProviderIdAndDate(PROVIDER_ID, "2026-11-20")).thenReturn(true);

        assertThatThrownBy(() -> service.createBlockedDate(PROVIDER_ID,
                new CreateBlockedDateRequest("2026-11-20", BlockReason.OTHER, null)))
                .isInstanceOf(ResourceConflictException.class);
        verify(blockedDateRepository, never()).save(any());
    }

    @Test
    @DisplayName("Returns 409 when the unique index rejects a duplicate saved at the same time")
    void createBlockedDateHandlesRace() {
        when(blockedDateRepository.existsByProviderIdAndDate(PROVIDER_ID, "2026-11-20")).thenReturn(false);
        when(blockedDateRepository.save(any(BlockedDate.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.createBlockedDate(PROVIDER_ID,
                new CreateBlockedDateRequest("2026-11-20", BlockReason.OTHER, null)))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    @DisplayName("Lists blocked dates from today by default")
    void getBlockedDatesUsesTodayByDefault() {
        when(blockedDateRepository.findInRange(eq(PROVIDER_ID), eq("2026-11-10"), eq("2027-11-10"), any(Sort.class)))
                .thenReturn(List.of());

        assertThat(service.getBlockedDates(PROVIDER_ID, null, null)).isEmpty();
    }

    @Test
    @DisplayName("Rejects a range where 'to' is before 'from'")
    void getBlockedDatesRejectsBadRange() {
        assertThatThrownBy(() -> service.getBlockedDates(PROVIDER_ID, "2026-12-10", "2026-12-01"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("INVALID_DATE_RANGE"));
    }

    @Test
    @DisplayName("Deletes the provider's own blocked date")
    void deleteBlockedDateSucceedsForOwner() {
        BlockedDate blockedDate = new BlockedDate();
        blockedDate.setId("bd-1");
        blockedDate.setProviderId(PROVIDER_ID);
        when(blockedDateRepository.findById("bd-1")).thenReturn(Optional.of(blockedDate));

        service.deleteBlockedDate(PROVIDER_ID, "bd-1");

        verify(blockedDateRepository).delete(blockedDate);
    }

    @Test
    @DisplayName("Denies deleting another provider's blocked date")
    void deleteBlockedDateDeniedForOtherProvider() {
        BlockedDate blockedDate = new BlockedDate();
        blockedDate.setId("bd-1");
        blockedDate.setProviderId("someone-else");
        when(blockedDateRepository.findById("bd-1")).thenReturn(Optional.of(blockedDate));

        assertThatThrownBy(() -> service.deleteBlockedDate(PROVIDER_ID, "bd-1"))
                .isInstanceOf(AccessDeniedException.class);
        verify(blockedDateRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Returns 404 when the blocked date does not exist")
    void deleteBlockedDateNotFound() {
        when(blockedDateRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBlockedDate(PROVIDER_ID, "missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
