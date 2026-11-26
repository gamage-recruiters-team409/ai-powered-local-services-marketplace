package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddress;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.CalendarFeedToken;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarSubscriptionResponse;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.booking.repository.CalendarFeedTokenRepository;

@ExtendWith(MockitoExtension.class)
class BookingCalendarServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private CalendarFeedTokenRepository tokenRepository;

    @Mock
    private AvailabilityFacade availabilityFacade;

    private BookingCalendarServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingCalendarServiceImpl(bookingRepository, tokenRepository, availabilityFacade,
                "https://api.localserve.lk/", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static Booking booking(String id, BookingStatus status, Instant start) {
        Booking b = new Booking();
        b.setId(id);
        b.setRef("BK-" + id);
        b.setProviderId(PROVIDER);
        b.setCustomerId("cust-1");
        b.setStatus(status);
        b.setSlotStart(start);
        b.setSlotEnd(start.plus(Duration.ofHours(2)));
        b.setAddress(new BookingAddress("12 Lake Rd", null, "Kandy", "Kandy", "20000"));
        return b;
    }

    @Test
    @DisplayName("Calendar week: working days, day off, blocked day and bookings with Colombo local times")
    void calendarWeek() {
        loginAs(PROVIDER, Role.PROVIDER);
        List<WeeklyHoursResponse> hours = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            hours.add(new WeeklyHoursResponse(d, d != DayOfWeek.SUNDAY, d != DayOfWeek.SUNDAY ? "08:00" : null,
                    d != DayOfWeek.SUNDAY ? "18:00" : null));
        }
        when(availabilityFacade.getSchedule(PROVIDER)).thenReturn(new ProviderSchedule(PROVIDER, hours, 30));
        when(availabilityFacade.getBlockedDates(PROVIDER, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26)))
                .thenReturn(List.of(new BlockedDateResponse("bd", "2026-10-24", BlockReason.PERSONAL, null, NOW)));
        when(bookingRepository.findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(eq(PROVIDER),
                anyCollection(), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>(List.of(booking("b2", BookingStatus.CONFIRMED,
                                Instant.parse("2026-10-24T03:30:00Z")),
                        booking("b1", BookingStatus.IN_PROGRESS, Instant.parse("2026-10-23T02:30:00Z")))));

        CalendarResponse cal = service.calendar("2026-10-20", "2026-10-26");

        assertThat(cal.days()).hasSize(7);
        assertThat(cal.days().get(0).working()).isTrue();
        assertThat(cal.days().get(0).startTime()).isEqualTo("08:00");
        // 2026-10-20 is a Tuesday: index 4 = Sat 24 (blocked), index 5 = Sun 25 (day off)
        assertThat(cal.days().get(4).dayOfWeek()).isEqualTo("SATURDAY");
        assertThat(cal.days().get(4).blocked()).isTrue();
        assertThat(cal.days().get(4).working()).isFalse();
        assertThat(cal.days().get(4).blockedReason()).isEqualTo("PERSONAL");
        assertThat(cal.days().get(5).working()).isFalse();
        assertThat(cal.days().get(5).blocked()).isFalse();
        assertThat(cal.days().get(6).working()).isTrue();
        assertThat(cal.events()).extracting(CalendarResponse.Event::bookingId).containsExactly("b1", "b2");
        assertThat(cal.events().get(0).localStart()).isEqualTo("08:00");
        assertThat(cal.events().get(1).localDate()).isEqualTo("2026-10-24");
    }

    @Test
    @DisplayName("Range longer than 42 days, reversed or bad dates → 400; nothing read")
    void badRanges() {
        loginAs(PROVIDER, Role.PROVIDER);
        assertThatThrownBy(() -> service.calendar("2026-10-01", "2026-11-12")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.calendar("2026-10-26", "2026-10-20")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.calendar("20-10-2026", "2026-10-26")).isInstanceOf(ApiException.class);
        verifyNoInteractions(availabilityFacade, bookingRepository);
    }

    @Test
    @DisplayName(".ics export: provider uses the provider query, district only, last 30 days included")
    void exportProvider() {
        loginAs(PROVIDER, Role.PROVIDER);
        when(bookingRepository.findTop500ByProviderIdAndStatusInAndSlotEndGreaterThanOrderBySlotStartAsc(eq(PROVIDER),
                anyCollection(), eq(NOW.minus(Duration.ofDays(30)))))
                .thenReturn(List.of(booking("b1", BookingStatus.CONFIRMED, Instant.parse("2026-12-01T03:30:00Z"))));

        String ics = service.exportIcs();

        assertThat(ics).contains("UID:b1@localserve", "LOCATION:Kandy").doesNotContain("12 Lake Rd", "20000");
        verify(bookingRepository, never())
                .findTop500ByCustomerIdAndStatusInAndSlotEndGreaterThanOrderBySlotStartAsc(any(), any(), any());
    }

    @Test
    @DisplayName("Create link: 43-char secret in the URL, only its SHA-256 hash stored, old link removed")
    void createLink() {
        loginAs("cust-1", Role.CUSTOMER);
        ArgumentCaptor<CalendarFeedToken> saved = ArgumentCaptor.forClass(CalendarFeedToken.class);

        CalendarSubscriptionResponse response = service.createOrResetSubscription();

        verify(tokenRepository).deleteByUserId("cust-1");
        verify(tokenRepository).save(saved.capture());
        String token = response.url().substring(response.url().lastIndexOf('/') + 1).replace(".ics", "");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.url()).startsWith("https://api.localserve.lk/api/v1/calendar/feed/");
        assertThat(response.webcalUrl()).startsWith("webcal://api.localserve.lk/");
        assertThat(saved.getValue().getTokenHash()).isEqualTo(BookingCalendarServiceImpl.hash(token))
                .isNotEqualTo(token);
        assertThat(saved.getValue().getRole()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("Get link status never returns the secret")
    void getLinkStatus() {
        loginAs(PROVIDER, Role.PROVIDER);
        CalendarFeedToken t = new CalendarFeedToken();
        t.setCreatedAt(NOW);
        when(tokenRepository.findByUserId(PROVIDER)).thenReturn(Optional.of(t));

        CalendarSubscriptionResponse status = service.getSubscription();

        assertThat(status.active()).isTrue();
        assertThat(status.url()).isNull();
        assertThat(status.webcalUrl()).isNull();
    }

    @Test
    @DisplayName("Feed: valid secret → that user's .ics; lastUsedAt updated at most once an hour")
    void feed() {
        String token = "A".repeat(43);
        CalendarFeedToken t = new CalendarFeedToken();
        t.setUserId(PROVIDER);
        t.setRole("PROVIDER");
        t.setLastUsedAt(NOW.minus(Duration.ofMinutes(10)));
        when(tokenRepository.findByTokenHash(BookingCalendarServiceImpl.hash(token))).thenReturn(Optional.of(t));
        when(bookingRepository.findTop500ByProviderIdAndStatusInAndSlotEndGreaterThanOrderBySlotStartAsc(eq(PROVIDER),
                anyCollection(), any(Instant.class))).thenReturn(List.of());

        assertThat(service.feed(token)).startsWith("BEGIN:VCALENDAR");
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("Feed: malformed secret → 404 without a database lookup; unknown secret → 404")
    void feedNotFound() {
        assertThatThrownBy(() -> service.feed("short")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.feed(null)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tokenRepository);

        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.feed("B".repeat(43))).isInstanceOf(ResourceNotFoundException.class);
    }
}
