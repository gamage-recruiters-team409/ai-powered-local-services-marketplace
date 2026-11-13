package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.TimeSlot;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class SlotServiceImplTest {

    private static final String PROVIDER = "p-1";

    // Friday 2026-11-20 08:00 in Asia/Colombo
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:30:00Z"), SlotServiceImpl.BUSINESS_ZONE);

    private static final String TODAY = "2026-11-20";     // Friday
    private static final String MONDAY = "2026-11-23";
    private static final String SUNDAY = "2026-11-22";

    @Mock
    private AvailabilityFacade availabilityFacade;

    @Mock
    private BookingRepository bookingRepository;

    private SlotServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SlotServiceImpl(availabilityFacade, bookingRepository, CLOCK);
    }

    /** Mon–Fri 09:00–17:00, weekend off, 30 minutes buffer. */
    private void givenWeekdaySchedule() {
        List<WeeklyHoursResponse> hours = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            boolean weekday = day.getValue() <= 5;
            hours.add(new WeeklyHoursResponse(day, weekday, weekday ? "09:00" : null, weekday ? "17:00" : null));
        }
        when(availabilityFacade.getSchedule(PROVIDER)).thenReturn(new ProviderSchedule(PROVIDER, hours, 30));
    }

    private void givenNotBlocked(String date) {
        when(availabilityFacade.isDateBlocked(PROVIDER, LocalDate.parse(date))).thenReturn(false);
    }

    private void givenExistingBookings(List<Booking> bookings) {
        when(bookingRepository.findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                eq(PROVIDER), eq(SlotServiceImpl.TIME_BLOCKING_STATUSES), any(Instant.class), any(Instant.class)))
                .thenReturn(bookings);
    }

    private static Booking booking(String startUtc, String endUtc) {
        Booking booking = new Booking();
        booking.setProviderId(PROVIDER);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setSlotStart(Instant.parse(startUtc));
        booking.setSlotEnd(Instant.parse(endUtc));
        return booking;
    }

    private static List<String> starts(AvailableSlotsResponse response) {
        return response.slots().stream().map(TimeSlot::localStart).toList();
    }

    @Test
    @DisplayName("Free working day: 60-minute slots every 30 minutes from 09:00 to 16:00 (15 slots)")
    void fullWorkingDay() {
        givenWeekdaySchedule();
        givenNotBlocked(MONDAY);
        givenExistingBookings(List.of());

        AvailableSlotsResponse response = service.getAvailableSlots(PROVIDER, MONDAY, "60");

        assertThat(response.dayStatus()).isEqualTo("WORKING");
        assertThat(response.timeZone()).isEqualTo("Asia/Colombo");
        assertThat(response.bufferMinutes()).isEqualTo(30);
        assertThat(response.slots()).hasSize(15);
        assertThat(starts(response)).startsWith("09:00", "09:30").endsWith("16:00");
        TimeSlot first = response.slots().get(0);
        assertThat(first.start()).isEqualTo(Instant.parse("2026-11-23T03:30:00Z"));
        assertThat(first.end()).isEqualTo(Instant.parse("2026-11-23T04:30:00Z"));
        assertThat(first.localEnd()).isEqualTo("10:00");
    }

    @Test
    @DisplayName("Today: slots start at least 3 hours from now (08:00 → first slot 11:00)")
    void minimumNoticeToday() {
        givenWeekdaySchedule();
        givenNotBlocked(TODAY);
        givenExistingBookings(List.of());

        AvailableSlotsResponse response = service.getAvailableSlots(PROVIDER, TODAY, "60");

        assertThat(starts(response)).startsWith("11:00").endsWith("16:00").hasSize(11);
    }

    @Test
    @DisplayName("Existing booking 12:00–13:00 with 30 min buffer removes 11:00–13:00 starts")
    void existingBookingAndBuffer() {
        givenWeekdaySchedule();
        givenNotBlocked(MONDAY);
        // 12:00–13:00 Colombo = 06:30–07:30 UTC
        givenExistingBookings(List.of(booking("2026-11-23T06:30:00Z", "2026-11-23T07:30:00Z")));

        AvailableSlotsResponse response = service.getAvailableSlots(PROVIDER, MONDAY, "60");

        assertThat(starts(response)).contains("10:30", "13:30")
                .doesNotContain("11:00", "11:30", "12:00", "12:30", "13:00")
                .hasSize(10);
    }

    @Test
    @DisplayName("Only time-holding statuses are queried (declined/cancelled/expired bookings free the slot)")
    void queriesOnlyTimeBlockingStatuses() {
        givenWeekdaySchedule();
        givenNotBlocked(MONDAY);
        givenExistingBookings(List.of());

        service.getAvailableSlots(PROVIDER, MONDAY, "60");

        verify(bookingRepository).findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                eq(PROVIDER), eq(SlotServiceImpl.TIME_BLOCKING_STATUSES), any(Instant.class), any(Instant.class));
        assertThat(SlotServiceImpl.TIME_BLOCKING_STATUSES).containsExactlyInAnyOrder(
                BookingStatus.PENDING_PROVIDER, BookingStatus.AWAITING_PAYMENT,
                BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Day off: DAY_OFF with no slots and no booking query")
    void dayOff() {
        givenWeekdaySchedule();
        givenNotBlocked(SUNDAY);

        AvailableSlotsResponse response = service.getAvailableSlots(PROVIDER, SUNDAY, "60");

        assertThat(response.dayStatus()).isEqualTo("DAY_OFF");
        assertThat(response.slots()).isEmpty();
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Blocked date: BLOCKED with no slots")
    void blockedDate() {
        givenWeekdaySchedule();
        when(availabilityFacade.isDateBlocked(PROVIDER, LocalDate.parse(MONDAY))).thenReturn(true);

        AvailableSlotsResponse response = service.getAvailableSlots(PROVIDER, MONDAY, "60");

        assertThat(response.dayStatus()).isEqualTo("BLOCKED");
        assertThat(response.slots()).isEmpty();
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("An 8-hour service fits exactly once in a 09:00–17:00 day; slots never pass the end of work")
    void slotMustEndBeforeWorkEnds() {
        givenWeekdaySchedule();
        givenNotBlocked(MONDAY);
        givenExistingBookings(List.of());

        AvailableSlotsResponse response = service.getAvailableSlots(PROVIDER, MONDAY, "480");

        assertThat(starts(response)).containsExactly("09:00");
        assertThat(response.slots().get(0).localEnd()).isEqualTo("17:00");
    }

    @ParameterizedTest(name = "providerId={0}, date={1}, duration={2} → 400 {3}")
    @CsvSource({
            "'', 2026-11-23, 60, INVALID_PROVIDER",
            "p-1, '', 60, INVALID_DATE",
            "p-1, 23-11-2026, 60, INVALID_DATE",
            "p-1, 2026-11-19, 60, INVALID_DATE",
            "p-1, 2027-11-21, 60, INVALID_DATE",
            "p-1, 2026-11-23, '', INVALID_DURATION",
            "p-1, 2026-11-23, abc, INVALID_DURATION",
            "p-1, 2026-11-23, 10, INVALID_DURATION",
            "p-1, 2026-11-23, 20, INVALID_DURATION",
            "p-1, 2026-11-23, 495, INVALID_DURATION"
    })
    void invalidInput(String providerId, String date, String duration, String code) {
        assertThatThrownBy(() -> service.getAvailableSlots(providerId, date, duration))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(code));
        verify(availabilityFacade, never()).getSchedule(anyString());
        verifyNoInteractions(bookingRepository);
    }
}
