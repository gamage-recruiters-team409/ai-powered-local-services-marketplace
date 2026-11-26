package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleRequest;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleStatus;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingNotifier notifier;

    private BookingExpiryJob job(boolean enabled) {
        return new BookingExpiryJob(bookingRepository, new BookingPaymentPolicy(15, 15), notifier,
                Clock.fixed(NOW, ZoneOffset.UTC), enabled);
    }

    private static Booking booking(String id, BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setStatus(status);
        return booking;
    }

    @Test
    @DisplayName("Expires unanswered requests and unpaid bookings as SYSTEM; payment cut-off includes the 15 min grace")
    void expiresBoth() {
        Booking request = booking("b-1", BookingStatus.PENDING_PROVIDER);
        Booking unpaid = booking("b-2", BookingStatus.AWAITING_PAYMENT);
        when(bookingRepository.findTop100ByStatusAndResponseDeadlineBeforeOrderByResponseDeadlineAsc(
                BookingStatus.PENDING_PROVIDER, NOW)).thenReturn(List.of(request));
        when(bookingRepository.findTop100ByStatusAndPaymentDueAtBeforeOrderByPaymentDueAtAsc(
                BookingStatus.AWAITING_PAYMENT, NOW.minusSeconds(15 * 60))).thenReturn(List.of(unpaid));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingExpiryJob.Result result = job(true).run();

        assertThat(result.requestsExpired()).isEqualTo(1);
        assertThat(result.paymentsExpired()).isEqualTo(1);
        assertThat(request.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(unpaid.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(request.getTimeline().get(0).getChangedBy()).isEqualTo(BookingStatusChange.SYSTEM);
        assertThat(request.getTimeline().get(0).getReason()).isEqualTo("Provider did not respond in time");
        assertThat(unpaid.getTimeline().get(0).getReason()).isEqualTo("Payment not completed in time");
        verify(notifier).requestExpired(request);
        verify(notifier).paymentExpired(unpaid);
    }

    @Test
    @DisplayName("Reschedule request not answered in time → EXPIRED, booking keeps its time, requester notified")
    void expiresRescheduleRequests() {
        Booking booking = booking("b-3", BookingStatus.CONFIRMED);
        booking.setSlotStart(NOW.plusSeconds(3600));
        RescheduleRequest late = new RescheduleRequest();
        late.setStatus(RescheduleStatus.PENDING);
        late.setRequestedBy("cust-1");
        late.setExpiresAt(NOW.minusSeconds(60));
        booking.getRescheduleRequests().add(late);
        when(bookingRepository.findWithExpiredRescheduleRequests(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingExpiryJob.Result result = job(true).run();

        assertThat(result.reschedulesExpired()).isEqualTo(1);
        assertThat(late.getStatus()).isEqualTo(RescheduleStatus.EXPIRED);
        assertThat(late.getRespondedBy()).isEqualTo(BookingStatusChange.SYSTEM);
        assertThat(booking.getSlotStart()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(notifier).rescheduleExpired(booking, late);
    }

    @Test
    @DisplayName("A booking changed at the same moment is skipped, the others still expire")
    void concurrentChangeSkipped() {
        Booking first = booking("b-1", BookingStatus.PENDING_PROVIDER);
        Booking second = booking("b-2", BookingStatus.PENDING_PROVIDER);
        when(bookingRepository.findTop100ByStatusAndResponseDeadlineBeforeOrderByResponseDeadlineAsc(
                BookingStatus.PENDING_PROVIDER, NOW)).thenReturn(List.of(first, second));
        when(bookingRepository.findTop100ByStatusAndPaymentDueAtBeforeOrderByPaymentDueAtAsc(
                any(), any())).thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class)))
                .thenThrow(new OptimisticLockingFailureException("v"))
                .thenAnswer(inv -> inv.getArgument(0));

        BookingExpiryJob.Result result = job(true).run();

        assertThat(result.requestsExpired()).isEqualTo(1);
        verify(bookingRepository, times(2)).save(any(Booking.class));
    }

    @Test
    @DisplayName("Disabled job does nothing")
    void disabled() {
        job(false).scheduledRun();

        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Payment policy: due 15 min after acceptance")
    void paymentPolicy() {
        BookingPaymentPolicy policy = new BookingPaymentPolicy(15, 15);

        assertThat(policy.paymentDueAt(NOW)).isEqualTo(NOW.plusSeconds(900));
        assertThat(policy.expireDueBefore(NOW)).isEqualTo(NOW.minusSeconds(900));
    }
}
