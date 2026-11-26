package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingBlockedDateCheckTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 1);

    @Mock
    private BookingRepository bookingRepository;

    private static Booking booking(String id, String startUtc) {
        Booking b = new Booking();
        b.setId(id);
        b.setStatus(BookingStatus.CONFIRMED);
        b.setSlotStart(Instant.parse(startUtc));
        b.setSlotEnd(Instant.parse(startUtc).plusSeconds(3600));
        return b;
    }

    private void givenOnDate(List<Booking> bookings) {
        when(bookingRepository.findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                eq("p-1"), anyCollection(), any(Instant.class), any(Instant.class))).thenReturn(bookings);
    }

    @Test
    @DisplayName("Empty day → can be blocked")
    void emptyDay() {
        givenOnDate(List.of());
        assertThatCode(() -> new BookingBlockedDateCheck(bookingRepository).assertNoBookingsToHandle("p-1", DATE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A booking on the day → 409 BOOKINGS_ON_DATE")
    void bookingOnDay() {
        givenOnDate(List.of(booking("b-1", "2026-12-01T04:30:00Z")));
        assertThatThrownBy(() -> new BookingBlockedDateCheck(bookingRepository).assertNoBookingsToHandle("p-1", DATE))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo("BOOKINGS_ON_DATE"));
    }

    @Test
    @DisplayName("Bookings already decided in the block-date flow are accepted")
    void handledBookingsAccepted() {
        givenOnDate(List.of(booking("b-1", "2026-12-01T04:30:00Z")));
        assertThatCode(() -> BlockDateContext.runWithHandled(Set.of("b-1"), () -> {
            new BookingBlockedDateCheck(bookingRepository).assertNoBookingsToHandle("p-1", DATE);
            return null;
        })).doesNotThrowAnyException();
        // outside the flow the same booking blocks again
        assertThatThrownBy(() -> new BookingBlockedDateCheck(bookingRepository).assertNoBookingsToHandle("p-1", DATE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("A booking that started the evening before (crossing midnight) is not counted")
    void startedDayBefore() {
        // 30 Nov 23:30 Colombo = 30 Nov 18:00 UTC
        givenOnDate(List.of(booking("b-1", "2026-11-30T18:00:00Z")));
        assertThatCode(() -> new BookingBlockedDateCheck(bookingRepository).assertNoBookingsToHandle("p-1", DATE))
                .doesNotThrowAnyException();
    }
}
