package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateAction;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateDecision;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateImpactResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateResult;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.mongodb.MongoException;

@ExtendWith(MockitoExtension.class)
class BookingBlockDateServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final String DATE = "2026-12-01";
    private static final Instant DAY_9AM = Instant.parse("2026-12-01T03:30:00Z");   // 09:00 Colombo
    private static final Instant DAY_2PM = Instant.parse("2026-12-01T08:30:00Z");   // 14:00 Colombo
    private static final Instant NEXT_DAY = Instant.parse("2026-12-02T03:30:00Z");
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private AvailabilityFacade availabilityFacade;

    @Mock
    private BookingRescheduleServiceImpl rescheduleService;

    @Mock
    private BookingNotifier notifier;

    @Mock
    private ProviderBookingGuard providerBookingGuard;

    private BookingBlockDateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = service(TransactionOperations.withoutTransaction());
        UserPrincipal principal = new UserPrincipal(PROVIDER, "p@test.local", Role.PROVIDER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private BookingBlockDateServiceImpl service(TransactionOperations transactions) {
        return new BookingBlockDateServiceImpl(bookingRepository, availabilityFacade, rescheduleService,
                new ReschedulePolicy(2, 6, 12), notifier, providerBookingGuard, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), 0);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Booking booking(String id, BookingStatus status, Instant start) {
        Booking b = new Booking();
        b.setId(id);
        b.setRef("BK-" + id);
        b.setProviderId(PROVIDER);
        b.setCustomerId("cust-" + id);
        b.setStatus(status);
        b.setSlotStart(start);
        b.setSlotEnd(start.plus(Duration.ofMinutes(120)));
        b.setDurationMinutes(120);
        return b;
    }

    private void givenOnDate(Booking... bookings) {
        when(bookingRepository.findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                eq(PROVIDER), anyCollection(), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>(List.of(bookings)));
    }

    private static BlockDateWithBookingsRequest request(BlockDateDecision... decisions) {
        return new BlockDateWithBookingsRequest(DATE, BlockReason.PERSONAL, "Family function", List.of(decisions));
    }

    private static void assertCode(Throwable e, String code) {
        assertThat(((ApiException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("Impact: confirmed / pending / awaiting payment grouped; canBlock false while a payment is open")
    void impact() {
        givenOnDate(booking("c", BookingStatus.CONFIRMED, DAY_9AM), booking("p", BookingStatus.PENDING_PROVIDER, DAY_2PM),
                booking("a", BookingStatus.AWAITING_PAYMENT, DAY_2PM));

        BlockDateImpactResponse impact = service.impact(DATE);

        assertThat(impact.confirmedBookings()).extracting(BlockDateImpactResponse.Item::bookingId).containsExactly("c");
        assertThat(impact.confirmedBookings().get(0).localStart()).isEqualTo("09:00");
        assertThat(impact.confirmedBookings().get(0).canRequestNewTime()).isTrue();
        assertThat(impact.pendingRequests()).extracting(BlockDateImpactResponse.Item::bookingId).containsExactly("p");
        assertThat(impact.awaitingPayment()).hasSize(1);
        assertThat(impact.canBlock()).isFalse();
    }

    @Test
    @DisplayName("Keep one, new time for another, pending declined → then the date is blocked with both accepted")
    void blockWithDecisions() {
        Booking keep = booking("k", BookingStatus.CONFIRMED, DAY_9AM);
        Booking move = booking("m", BookingStatus.CONFIRMED, DAY_2PM);
        Booking pending = booking("p", BookingStatus.PENDING_PROVIDER, DAY_2PM);
        givenOnDate(keep, move, pending);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        BlockedDateResponse blocked = new BlockedDateResponse("bd-1", DATE, BlockReason.PERSONAL, "Family function", NOW);
        List<Set<String>> handledDuringBlock = new ArrayList<>();
        when(availabilityFacade.createBlockedDate(eq(PROVIDER), any(CreateBlockedDateRequest.class)))
                .thenAnswer(inv -> {
                    handledDuringBlock.add(BlockDateContext.handled());
                    return blocked;
                });

        BlockDateResult result = service.blockWithBookings(request(
                new BlockDateDecision("k", BlockDateAction.KEEP, null, null),
                new BlockDateDecision("m", BlockDateAction.REQUEST_NEW_TIME, NEXT_DAY, "Sorry, family function")));

        assertThat(result.blockedDate()).isSameAs(blocked);
        assertThat(result.keptBookingIds()).containsExactly("k");
        assertThat(result.newTimeRequestedBookingIds()).containsExactly("m");
        assertThat(result.declinedBookingIds()).containsExactly("p");
        assertThat(pending.getStatus()).isEqualTo(BookingStatus.DECLINED);
        assertThat(pending.getTimeline().get(0).getReason()).isEqualTo(BookingBlockDateServiceImpl.DECLINE_REASON);
        verify(notifier).declined(pending, BookingDeclineReason.NOT_AVAILABLE);
        verify(rescheduleService).validateNewRequest(move, NEXT_DAY, NOW);
        verify(rescheduleService).submit(move, PROVIDER, NEXT_DAY, "Sorry, family function", NOW);
        assertThat(handledDuringBlock).containsExactly(Set.of("k", "m"));
        assertThat(BlockDateContext.handled()).isEmpty();
        // The provider guard is claimed before the bookings are read (serialised with booking creation).
        InOrder order = inOrder(providerBookingGuard, bookingRepository);
        order.verify(providerBookingGuard).ensureExists(PROVIDER, NOW);
        order.verify(providerBookingGuard).claim(PROVIDER, NOW);
        order.verify(bookingRepository).findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                eq(PROVIDER), anyCollection(), any(Instant.class), any(Instant.class));
    }

    @Test
    @DisplayName("No MongoDB transaction manager → 503 BLOCK_DATE_UNAVAILABLE, nothing read or changed")
    void failsClosedWithoutTransactions() {
        assertThatThrownBy(() -> service(null).blockWithBookings(request()))
                .satisfies(e -> assertCode(e, "BLOCK_DATE_UNAVAILABLE"));
        verifyNoInteractions(bookingRepository, availabilityFacade, providerBookingGuard, notifier);
    }

    @Test
    @DisplayName("Transient transaction conflict (a booking for the provider committing) → retried with fresh data")
    void retriesTransientConflict() {
        Booking pending = booking("p", BookingStatus.PENDING_PROVIDER, DAY_2PM);
        givenOnDate(pending);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(availabilityFacade.createBlockedDate(eq(PROVIDER), any(CreateBlockedDateRequest.class)))
                .thenReturn(new BlockedDateResponse("bd-1", DATE, BlockReason.PERSONAL, null, NOW));
        AtomicInteger calls = new AtomicInteger();
        TransactionOperations flaky = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                if (calls.incrementAndGet() == 1) {
                    MongoException conflict = new MongoException("WriteConflict");
                    conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw conflict;
                }
                return action.doInTransaction((TransactionStatus) null);
            }
        };

        assertThat(service(flaky).blockWithBookings(request()).declinedBookingIds()).containsExactly("p");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("A confirmed booking without a decision → 409 DECISION_REQUIRED, nothing changed")
    void decisionRequired() {
        givenOnDate(booking("k", BookingStatus.CONFIRMED, DAY_9AM), booking("p", BookingStatus.PENDING_PROVIDER, DAY_2PM));

        assertThatThrownBy(() -> service.blockWithBookings(request()))
                .satisfies(e -> assertCode(e, "DECISION_REQUIRED"));
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(availabilityFacade, never()).createBlockedDate(any(), any());
    }

    @Test
    @DisplayName("Payment in progress on that date → 409 BLOCK_DATE_PAYMENT_IN_PROGRESS")
    void paymentInProgress() {
        givenOnDate(booking("a", BookingStatus.AWAITING_PAYMENT, DAY_9AM));

        assertThatThrownBy(() -> service.blockWithBookings(request()))
                .satisfies(e -> assertCode(e, "BLOCK_DATE_PAYMENT_IN_PROGRESS"));
        verify(availabilityFacade, never()).createBlockedDate(any(), any());
    }

    @Test
    @DisplayName("Decision for a booking that is not on that date, or two decisions for one booking → 400")
    void badDecisions() {
        givenOnDate(booking("k", BookingStatus.CONFIRMED, DAY_9AM));

        assertThatThrownBy(() -> service.blockWithBookings(request(
                new BlockDateDecision("other", BlockDateAction.KEEP, null, null))))
                .satisfies(e -> assertCode(e, "UNKNOWN_BOOKING_DECISION"));
        assertThatThrownBy(() -> service.blockWithBookings(request(
                new BlockDateDecision("k", BlockDateAction.KEEP, null, null),
                new BlockDateDecision("k", BlockDateAction.KEEP, null, null))))
                .satisfies(e -> assertCode(e, "DUPLICATE_DECISION"));
    }

    @Test
    @DisplayName("New time missing, or on the blocked date itself → 400")
    void badNewTime() {
        givenOnDate(booking("m", BookingStatus.CONFIRMED, DAY_9AM));

        assertThatThrownBy(() -> service.blockWithBookings(request(
                new BlockDateDecision("m", BlockDateAction.REQUEST_NEW_TIME, null, null))))
                .satisfies(e -> assertCode(e, "NEW_TIME_REQUIRED"));
        assertThatThrownBy(() -> service.blockWithBookings(request(
                new BlockDateDecision("m", BlockDateAction.REQUEST_NEW_TIME, DAY_2PM, null))))
                .satisfies(e -> assertCode(e, "NEW_TIME_ON_BLOCKED_DATE"));
    }

    @Test
    @DisplayName("A new time that breaks a reschedule rule stops everything before any booking changes")
    void invalidNewTimeChangesNothing() {
        Booking move = booking("m", BookingStatus.CONFIRMED, DAY_2PM);
        Booking pending = booking("p", BookingStatus.PENDING_PROVIDER, DAY_9AM);
        givenOnDate(pending, move);
        doThrow(new ResourceConflictException("SLOT_UNAVAILABLE", "taken"))
                .when(rescheduleService).validateNewRequest(move, NEXT_DAY, NOW);

        assertThatThrownBy(() -> service.blockWithBookings(request(
                new BlockDateDecision("m", BlockDateAction.REQUEST_NEW_TIME, NEXT_DAY, null))))
                .satisfies(e -> assertCode(e, "SLOT_UNAVAILABLE"));
        assertThat(pending.getStatus()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(rescheduleService, never()).submit(any(), any(), any(), any(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Already blocked → 409; past date → 400; bad date → 400")
    void dateChecks() {
        when(availabilityFacade.isDateBlocked(PROVIDER, LocalDate.parse(DATE))).thenReturn(true);
        assertThatThrownBy(() -> service.blockWithBookings(request()))
                .satisfies(e -> assertCode(e, "BLOCKED_DATE_EXISTS"));

        assertThatThrownBy(() -> service.blockWithBookings(new BlockDateWithBookingsRequest("2026-11-01",
                BlockReason.OTHER, null, null))).satisfies(e -> assertCode(e, "INVALID_BLOCKED_DATE"));
        assertThatThrownBy(() -> service.impact("01-12-2026")).satisfies(e -> assertCode(e, "INVALID_DATE"));
    }
}
