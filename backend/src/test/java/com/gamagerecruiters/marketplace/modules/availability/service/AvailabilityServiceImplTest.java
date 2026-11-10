package com.gamagerecruiters.marketplace.modules.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;
import com.gamagerecruiters.marketplace.modules.availability.domain.WeeklyHours;
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

    @Mock
    private MongoTemplate mongoTemplate;

    private AvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AvailabilityServiceImpl(availabilityRuleRepository, blockedDateRepository, mongoTemplate, FIXED_CLOCK);
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

    private static AvailabilityRule storedRule(int bufferMinutes) {
        AvailabilityRule rule = new AvailabilityRule();
        rule.setId("rule-1");
        rule.setProviderId(PROVIDER_ID);
        List<WeeklyHours> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new WeeklyHours(day, true, "09:00", "17:00"));
        }
        rule.setWeeklyHours(days);
        rule.setBufferMinutes(bufferMinutes);
        rule.setUpdatedAt(Instant.now(FIXED_CLOCK));
        return rule;
    }

    @Test
    @DisplayName("Saves a valid weekly schedule with one atomic upsert")
    void updateAvailabilityUsesAtomicUpsert() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AvailabilityRule.class))).thenReturn(storedRule(45));

        AvailabilityResponse response =
                service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(fullWeek(), 45));

        assertThat(response.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(response.bufferMinutes()).isEqualTo(45);
        assertThat(response.weeklyHours()).hasSize(7);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(query.capture(), update.capture(), options.capture(),
                eq(AvailabilityRule.class));

        assertThat(query.getValue().getQueryObject().getString("providerId")).isEqualTo(PROVIDER_ID);
        assertThat(options.getValue().isUpsert()).isTrue();
        assertThat(options.getValue().isReturnNew()).isTrue();

        Document set = update.getValue().getUpdateObject().get("$set", Document.class);
        Document setOnInsert = update.getValue().getUpdateObject().get("$setOnInsert", Document.class);
        assertThat(set.get("bufferMinutes")).isEqualTo(45);
        assertThat(set.get("updatedAt")).isEqualTo(Instant.now(FIXED_CLOCK));
        assertThat(setOnInsert.get("createdAt")).isEqualTo(Instant.now(FIXED_CLOCK));

        @SuppressWarnings("unchecked")
        List<WeeklyHours> savedDays = (List<WeeklyHours>) set.get("weeklyHours");
        assertThat(savedDays.get(0).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        verify(availabilityRuleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Concurrent first-time update: retries once after a duplicate key and returns the saved schedule")
    void updateAvailabilityRetriesAfterDuplicateKey() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AvailabilityRule.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"))
                .thenReturn(storedRule(30));

        AvailabilityResponse response =
                service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(fullWeek(), 30));

        assertThat(response.providerId()).isEqualTo(PROVIDER_ID);
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AvailabilityRule.class));
    }

    @Test
    @DisplayName("Concurrent update: returns 409 (not 500) when the conflict continues after retry")
    void updateAvailabilityReturnsConflictWhenRetryFails() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AvailabilityRule.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"));

        assertThatThrownBy(() -> service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(fullWeek(), 30)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("AVAILABILITY_UPDATE_CONFLICT"));
        verify(mongoTemplate, times(AvailabilityServiceImpl.MAX_UPSERT_ATTEMPTS)).findAndModify(any(Query.class),
                any(Update.class), any(FindAndModifyOptions.class), eq(AvailabilityRule.class));
    }

    @Test
    @DisplayName("Rejects a schedule where the same day appears twice")
    void updateAvailabilityRejectsDuplicateDay() {
        List<WeeklyHoursRequest> days = fullWeek();
        days.set(6, new WeeklyHoursRequest(DayOfWeek.MONDAY, false, null, null));

        assertThatThrownBy(() -> service.updateAvailability(PROVIDER_ID, new UpdateAvailabilityRequest(days, 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("INVALID_WEEKLY_HOURS"));
        verifyNoInteractions(mongoTemplate);
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
