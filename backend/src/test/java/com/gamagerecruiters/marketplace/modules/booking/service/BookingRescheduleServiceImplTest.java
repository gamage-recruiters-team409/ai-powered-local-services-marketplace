package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleRequest;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleResponse;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.mongodb.MongoException;

@ExtendWith(MockitoExtension.class)
class BookingRescheduleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(2));
    private static final Instant NEW_START = START.plus(Duration.ofDays(1));
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private SlotService slotService;

    @Mock
    private ProviderBookingGuard providerBookingGuard;

    @Mock
    private BookingNotifier notifier;

    private Booking booking;

    @BeforeEach
    void setUp() {
        booking = new Booking();
        booking.setId("b-1");
        booking.setRef("BK-1");
        booking.setCustomerId(CUSTOMER);
        booking.setProviderId(PROVIDER);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setSlotStart(START);
        booking.setSlotEnd(START.plus(Duration.ofMinutes(120)));
        booking.setDurationMinutes(120);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private BookingRescheduleServiceImpl service(TransactionOperations transactions) {
        return new BookingRescheduleServiceImpl(bookingRepository, slotService, providerBookingGuard,
                new ReschedulePolicy(2, 6, 12), notifier, transactions, Clock.fixed(NOW, ZoneOffset.UTC), 0);
    }

    private BookingRescheduleServiceImpl service() {
        return service(TransactionOperations.withoutTransaction());
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void givenStored() {
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking));
    }

    private void givenSaveReturnsArgument() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RescheduleRequest pending(String requestedBy, Instant expiresAt) {
        RescheduleRequest r = new RescheduleRequest();
        r.setId("r-1");
        r.setRequestedBy(requestedBy);
        r.setRequestedByRole(CUSTOMER.equals(requestedBy) ? "CUSTOMER" : "PROVIDER");
        r.setPreviousSlotStart(START);
        r.setPreviousSlotEnd(START.plus(Duration.ofMinutes(120)));
        r.setProposedSlotStart(NEW_START);
        r.setProposedSlotEnd(NEW_START.plus(Duration.ofMinutes(120)));
        r.setStatus(RescheduleStatus.PENDING);
        r.setRequestedAt(NOW.minus(Duration.ofHours(1)));
        r.setExpiresAt(expiresAt);
        booking.getRescheduleRequests().add(r);
        return r;
    }

    private void approvedTimes(int n) {
        for (int i = 0; i < n; i++) {
            RescheduleRequest r = new RescheduleRequest();
            r.setStatus(RescheduleStatus.APPROVED);
            booking.getRescheduleRequests().add(r);
        }
    }

    private static void assertCode(Throwable e, String code) {
        assertThat(((ApiException) e).getErrorCode()).isEqualTo(code);
    }

    // ---------- request ----------

    @Test
    @DisplayName("Customer proposes a new time: PENDING request, 12 h to answer, provider notified")
    void customerRequests() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();
        when(slotService.isSlotAvailable(PROVIDER, NEW_START, 120, "b-1")).thenReturn(true);
        givenSaveReturnsArgument();

        RescheduleResponse response = service().request("b-1", new RescheduleBookingRequest(NEW_START, " Exam "));

        assertThat(response.slotStart()).isEqualTo(START);
        assertThat(response.reschedule().status()).isEqualTo(RescheduleStatus.PENDING);
        assertThat(response.reschedule().requestedByRole()).isEqualTo("CUSTOMER");
        assertThat(response.reschedule().proposedSlotEnd()).isEqualTo(NEW_START.plus(Duration.ofMinutes(120)));
        assertThat(response.reschedule().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(response.reschedule().note()).isEqualTo("Exam");
        assertThat(response.remainingReschedules()).isEqualTo(2);
        verify(notifier).rescheduleRequested(any(Booking.class), any(RescheduleRequest.class));
    }

    @Test
    @DisplayName("Start in 8 h: answer deadline is the current start, not 12 h")
    void expiresAtCappedByStart() {
        booking.setSlotStart(NOW.plus(Duration.ofHours(8)));
        loginAs(PROVIDER, Role.PROVIDER);
        givenStored();
        when(slotService.isSlotAvailable(PROVIDER, NEW_START, 120, "b-1")).thenReturn(true);
        givenSaveReturnsArgument();

        RescheduleResponse response = service().request("b-1", new RescheduleBookingRequest(NEW_START, null));

        assertThat(response.reschedule().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
        assertThat(response.reschedule().requestedByRole()).isEqualTo("PROVIDER");
    }

    @Test
    @DisplayName("Less than 6 h before the start → 409 RESCHEDULE_TOO_LATE")
    void tooLate() {
        booking.setSlotStart(NOW.plus(Duration.ofHours(5)));
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();

        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(NEW_START, null)))
                .satisfies(e -> assertCode(e, "RESCHEDULE_TOO_LATE"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Not CONFIRMED → 409 RESCHEDULE_NOT_ALLOWED")
    void notConfirmed() {
        booking.setStatus(BookingStatus.AWAITING_PAYMENT);
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();

        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(NEW_START, null)))
                .satisfies(e -> assertCode(e, "RESCHEDULE_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("Another request already waiting → 409 RESCHEDULE_ALREADY_PENDING")
    void alreadyPending() {
        pending(PROVIDER, NOW.plus(Duration.ofHours(5)));
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();

        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(NEW_START, null)))
                .satisfies(e -> assertCode(e, "RESCHEDULE_ALREADY_PENDING"));
    }

    @Test
    @DisplayName("Already moved twice → 409 RESCHEDULE_LIMIT_REACHED")
    void limitReached() {
        approvedTimes(2);
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();

        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(NEW_START, null)))
                .satisfies(e -> assertCode(e, "RESCHEDULE_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("Same time as now → 400 RESCHEDULE_SAME_TIME")
    void sameTime() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();

        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(START, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrorCode()).isEqualTo("RESCHEDULE_SAME_TIME");
                });
    }

    @Test
    @DisplayName("Proposed slot not free → 409 SLOT_UNAVAILABLE, nothing saved, nobody notified")
    void slotNotFree() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();
        when(slotService.isSlotAvailable(PROVIDER, NEW_START, 120, "b-1")).thenReturn(false);

        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(NEW_START, null)))
                .satisfies(e -> assertCode(e, "SLOT_UNAVAILABLE"));
        verify(bookingRepository, never()).save(any(Booking.class));
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Someone else's booking → 403; missing booking → 404")
    void accessChecks() {
        loginAs("cust-2", Role.CUSTOMER);
        givenStored();
        assertThatThrownBy(() -> service().request("b-1", new RescheduleBookingRequest(NEW_START, null)))
                .isInstanceOf(AccessDeniedException.class);

        when(bookingRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().approve("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- approve ----------

    @Test
    @DisplayName("Provider approves the customer's request: booking moves, guard claimed, customer notified")
    void approve() {
        RescheduleRequest request = pending(CUSTOMER, NOW.plus(Duration.ofHours(5)));
        loginAs(PROVIDER, Role.PROVIDER);
        givenStored();
        when(slotService.isSlotAvailable(PROVIDER, NEW_START, 120, "b-1")).thenReturn(true);
        givenSaveReturnsArgument();

        RescheduleResponse response = service().approve("b-1");

        assertThat(response.slotStart()).isEqualTo(NEW_START);
        assertThat(response.slotEnd()).isEqualTo(NEW_START.plus(Duration.ofMinutes(120)));
        assertThat(request.getStatus()).isEqualTo(RescheduleStatus.APPROVED);
        assertThat(request.getRespondedBy()).isEqualTo(PROVIDER);
        assertThat(response.remainingReschedules()).isEqualTo(1);
        verify(providerBookingGuard).ensureExists(PROVIDER, NOW);
        verify(providerBookingGuard).claim(PROVIDER, NOW);
        verify(notifier).rescheduleApproved(booking, request);
    }

    @Test
    @DisplayName("Answering your own request → 409 RESCHEDULE_OWN_REQUEST")
    void approveOwn() {
        pending(CUSTOMER, NOW.plus(Duration.ofHours(5)));
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();

        assertThatThrownBy(() -> service().approve("b-1")).satisfies(e -> assertCode(e, "RESCHEDULE_OWN_REQUEST"));
        assertThatThrownBy(() -> service().reject("b-1")).satisfies(e -> assertCode(e, "RESCHEDULE_OWN_REQUEST"));
    }

    @Test
    @DisplayName("Answer after the deadline → 409 RESCHEDULE_REQUEST_EXPIRED")
    void approveExpired() {
        pending(CUSTOMER, NOW);
        loginAs(PROVIDER, Role.PROVIDER);
        givenStored();

        assertThatThrownBy(() -> service().approve("b-1"))
                .satisfies(e -> assertCode(e, "RESCHEDULE_REQUEST_EXPIRED"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Proposed time taken meanwhile → 409 RESCHEDULE_SLOT_UNAVAILABLE, booking unchanged")
    void approveSlotTaken() {
        RescheduleRequest request = pending(CUSTOMER, NOW.plus(Duration.ofHours(5)));
        loginAs(PROVIDER, Role.PROVIDER);
        givenStored();
        when(slotService.isSlotAvailable(PROVIDER, NEW_START, 120, "b-1")).thenReturn(false);

        assertThatThrownBy(() -> service().approve("b-1"))
                .satisfies(e -> assertCode(e, "RESCHEDULE_SLOT_UNAVAILABLE"));
        assertThat(booking.getSlotStart()).isEqualTo(START);
        assertThat(request.getStatus()).isEqualTo(RescheduleStatus.PENDING);
        verify(bookingRepository, never()).save(any(Booking.class));
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Transient transaction conflict (another booking for the provider committing) → retried")
    void approveRetriesTransientConflict() {
        pending(CUSTOMER, NOW.plus(Duration.ofHours(5)));
        loginAs(PROVIDER, Role.PROVIDER);
        givenStored();
        when(slotService.isSlotAvailable(PROVIDER, NEW_START, 120, "b-1")).thenReturn(true);
        givenSaveReturnsArgument();
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

        assertThat(service(flaky).approve("b-1").slotStart()).isEqualTo(NEW_START);
        assertThat(calls.get()).isEqualTo(2);
    }

    // ---------- reject / withdraw ----------

    @Test
    @DisplayName("Customer rejects the provider's request: time unchanged, provider notified")
    void reject() {
        RescheduleRequest request = pending(PROVIDER, NOW.plus(Duration.ofHours(5)));
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();
        givenSaveReturnsArgument();

        RescheduleResponse response = service().reject("b-1");

        assertThat(response.slotStart()).isEqualTo(START);
        assertThat(request.getStatus()).isEqualTo(RescheduleStatus.REJECTED);
        assertThat(response.remainingReschedules()).isEqualTo(2);
        verify(notifier).rescheduleRejected(booking, request);
    }

    @Test
    @DisplayName("Requester withdraws: WITHDRAWN; the other party cannot withdraw; nothing pending → 409")
    void withdraw() {
        RescheduleRequest request = pending(CUSTOMER, NOW.plus(Duration.ofHours(5)));
        givenStored();

        loginAs(PROVIDER, Role.PROVIDER);
        assertThatThrownBy(() -> service().withdraw("b-1"))
                .satisfies(e -> assertCode(e, "RESCHEDULE_NOT_REQUESTER"));

        loginAs(CUSTOMER, Role.CUSTOMER);
        givenSaveReturnsArgument();
        assertThat(service().withdraw("b-1").reschedule().status()).isEqualTo(RescheduleStatus.WITHDRAWN);
        assertThat(request.getStatus()).isEqualTo(RescheduleStatus.WITHDRAWN);

        assertThatThrownBy(() -> service().withdraw("b-1"))
                .satisfies(e -> assertCode(e, "NO_PENDING_RESCHEDULE"));
    }

    // ---------- slots ----------

    @Test
    @DisplayName("Reschedule slots use the booking's provider and length and ignore the booking's own time")
    void slots() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenStored();
        AvailableSlotsResponse slots = new AvailableSlotsResponse(PROVIDER, "2026-11-23", "Asia/Colombo", 120, 30,
                "WORKING", List.of());
        when(slotService.getAvailableSlots(PROVIDER, "2026-11-23", "120", "b-1")).thenReturn(slots);

        assertThat(service().availableSlots("b-1", "2026-11-23")).isSameAs(slots);
    }

    @Test
    @DisplayName("Not logged in → 401")
    void notLoggedIn() {
        assertThatThrownBy(() -> service().approve("b-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(bookingRepository);
    }
}
