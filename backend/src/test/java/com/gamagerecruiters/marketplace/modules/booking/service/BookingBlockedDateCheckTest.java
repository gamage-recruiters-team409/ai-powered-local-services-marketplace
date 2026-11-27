package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingBlockedDateCheckTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 1);
    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ProviderBookingGuard providerBookingGuard;

    private final AtomicInteger created = new AtomicInteger();

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private BookingBlockedDateCheck check(TransactionOperations transactions) {
        return new BookingBlockedDateCheck(bookingRepository, providerBookingGuard, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BookingBlockedDateCheck check() {
        return check(TransactionOperations.withoutTransaction());
    }

    private String create() {
        created.incrementAndGet();
        return "blocked";
    }

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
    @DisplayName("Empty day: guard claimed BEFORE the booking check, then the date is saved")
    void emptyDayClaimsGuardThenSaves() {
        givenOnDate(List.of());

        assertThat(check().createIfNoBookingsToHandle("p-1", DATE, this::create)).isEqualTo("blocked");

        assertThat(created.get()).isEqualTo(1);
        InOrder order = inOrder(providerBookingGuard, bookingRepository);
        order.verify(providerBookingGuard).ensureExists("p-1", NOW);
        order.verify(providerBookingGuard).claim("p-1", NOW);
        order.verify(bookingRepository).findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                eq("p-1"), anyCollection(), any(Instant.class), any(Instant.class));
    }

    @Test
    @DisplayName("A booking on the day → 409 BOOKINGS_ON_DATE and the date is not saved")
    void bookingOnDay() {
        givenOnDate(List.of(booking("b-1", "2026-12-01T04:30:00Z")));

        assertThatThrownBy(() -> check().createIfNoBookingsToHandle("p-1", DATE, this::create))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("BOOKINGS_ON_DATE"));
        assertThat(created.get()).isZero();
    }

    @Test
    @DisplayName("No MongoDB transaction manager → 503 BLOCK_DATE_UNAVAILABLE, nothing read or saved")
    void failsClosedWithoutTransactions() {
        assertThatThrownBy(() -> check(null).createIfNoBookingsToHandle("p-1", DATE, this::create))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("BLOCK_DATE_UNAVAILABLE");
                });
        assertThat(created.get()).isZero();
        verifyNoInteractions(bookingRepository, providerBookingGuard);
    }

    @Test
    @DisplayName("Inside the block-date flow's transaction: accepts the decided bookings and joins (no second claim)")
    void insideBlockDateFlow() {
        givenOnDate(List.of(booking("b-1", "2026-12-01T04:30:00Z")));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        String result = BlockDateContext.runWithHandled(Set.of("b-1"),
                () -> check(null).createIfNoBookingsToHandle("p-1", DATE, this::create));

        assertThat(result).isEqualTo("blocked");
        verifyNoInteractions(providerBookingGuard);
    }

    @Test
    @DisplayName("A booking that started the evening before (crossing midnight) is not counted")
    void startedDayBefore() {
        // 30 Nov 23:30 Colombo = 30 Nov 18:00 UTC
        givenOnDate(List.of(booking("b-1", "2026-11-30T18:00:00Z")));

        assertThat(check().createIfNoBookingsToHandle("p-1", DATE, this::create)).isEqualTo("blocked");
    }
}
