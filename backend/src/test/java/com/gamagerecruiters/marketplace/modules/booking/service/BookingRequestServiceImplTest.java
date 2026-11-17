package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.mongodb.MongoException;

@ExtendWith(MockitoExtension.class)
class BookingRequestServiceImplTest {

    // Friday 2026-11-20 08:00 Colombo
    private static final Instant NOW = Instant.parse("2026-11-20T02:30:00Z");
    // Monday 2026-11-23 09:00–10:00 Colombo
    private static final Instant SLOT_START = Instant.parse("2026-11-23T03:30:00Z");
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private SlotService slotService;

    @Mock
    private ProviderBookingGuard providerBookingGuard;

    @Mock
    private BookingReferenceGenerator referenceGenerator;

    private BookingRequestServiceImpl enabledService;

    @BeforeEach
    void setUp() {
        enabledService = service(true, TransactionOperations.withoutTransaction());
    }

    private BookingRequestServiceImpl service(boolean enabled, TransactionOperations transactions) {
        return new BookingRequestServiceImpl(bookingRepository, slotService, providerBookingGuard,
                referenceGenerator, transactions, enabled, Clock.fixed(NOW, ZoneOffset.UTC), 0);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static CreateBookingRequest request(String providerId) {
        return new CreateBookingRequest(providerId, "listing-1", SLOT_START, 60,
                new BookingAddressRequest("  12 Temple Road ", null, "Nugegoda", "Colombo", "10250"),
                "  Gate code 1234 ");
    }

    private static UncategorizedMongoDbException writeConflict() {
        MongoException conflict = new MongoException(112, "WriteConflict");
        conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return new UncategorizedMongoDbException("Write conflict during transaction", conflict);
    }

    private void givenInsertAssignsId(String id) {
        when(bookingRepository.insert(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(id);
            return b;
        });
    }

    @Test
    @DisplayName("Gate: creation disabled (default) → 503 BOOKING_CREATION_UNAVAILABLE, nothing touched")
    void creationDisabledByDefault() {
        loginAs(CUSTOMER, Role.CUSTOMER);

        assertThatThrownBy(() -> service(false, TransactionOperations.withoutTransaction())
                .createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_CREATION_UNAVAILABLE");
                });
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Gate: no MongoDB transaction manager available → 503 (fail closed)")
    void noTransactionManagerFailsClosed() {
        loginAs(CUSTOMER, Role.CUSTOMER);

        assertThatThrownBy(() -> service(true, null).createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_CREATION_UNAVAILABLE"));
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Enabled: creates PENDING_PROVIDER booking; guard claimed before slot re-check")
    void createsBooking() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-ABCDEF");
        givenInsertAssignsId("booking-1");

        BookingResponse response = enabledService.createBookingRequest(request(PROVIDER));

        assertThat(response.id()).isEqualTo("booking-1");
        assertThat(response.ref()).isEqualTo("BK-20261120-ABCDEF");
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        assertThat(response.customerId()).isEqualTo(CUSTOMER);
        assertThat(response.slotEnd()).isEqualTo(Instant.parse("2026-11-23T04:30:00Z"));
        assertThat(response.localStart()).isEqualTo("09:00");
        assertThat(response.responseDeadline()).isEqualTo(Instant.parse("2026-11-20T14:30:00Z"));
        assertThat(response.address().line1()).isEqualTo("12 Temple Road");
        assertThat(response.notes()).isEqualTo("Gate code 1234");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(providerBookingGuard, slotService, bookingRepository);
        order.verify(providerBookingGuard).ensureExists(PROVIDER, NOW);
        order.verify(providerBookingGuard).claim(PROVIDER, NOW);
        order.verify(slotService).isSlotAvailable(PROVIDER, SLOT_START, 60);
        order.verify(bookingRepository).insert(argThat((Booking b) ->
                b.getTimeline().size() == 1 && CUSTOMER.equals(b.getTimeline().get(0).getChangedBy())));
    }

    @Test
    @DisplayName("Response deadline (C-02) is 1 h before the start when that is earlier than 12 h")
    void responseDeadlineBeforeStart() {
        assertThat(BookingRequestServiceImpl.responseDeadline(Instant.parse("2026-11-20T02:30:00Z"),
                Instant.parse("2026-11-20T07:30:00Z"))).isEqualTo(Instant.parse("2026-11-20T06:30:00Z"));
    }

    @Test
    @DisplayName("Not logged in: 401 UNAUTHORIZED")
    void unauthenticated() {
        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Booking yourself: 400 SELF_BOOKING_NOT_ALLOWED")
    void selfBooking() {
        loginAs(PROVIDER, Role.CUSTOMER);

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SELF_BOOKING_NOT_ALLOWED"));
        verifyNoInteractions(providerBookingGuard, bookingRepository);
    }

    @Test
    @DisplayName("Slot no longer available: 409 SLOT_UNAVAILABLE, nothing saved")
    void slotUnavailable() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(referenceGenerator.next(NOW)).thenReturn("BK-1");
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(false);

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SLOT_UNAVAILABLE"));
        verify(bookingRepository, never()).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Transaction write conflict: retried, then succeeds")
    void writeConflictIsRetried() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(referenceGenerator.next(NOW)).thenReturn("BK-1", "BK-2");
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        org.mockito.Mockito.doThrow(writeConflict()).doNothing().when(providerBookingGuard).claim(PROVIDER, NOW);
        givenInsertAssignsId("booking-2");

        BookingResponse response = enabledService.createBookingRequest(request(PROVIDER));

        assertThat(response.id()).isEqualTo("booking-2");
        verify(providerBookingGuard, times(2)).claim(PROVIDER, NOW);
        verify(bookingRepository, times(1)).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Write conflict on every attempt: 409 BOOKING_BUSY after the retry budget, nothing saved")
    void persistentConflictReturnsBusy() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(referenceGenerator.next(NOW)).thenReturn("BK-1");
        org.mockito.Mockito.doThrow(writeConflict()).when(providerBookingGuard).claim(eq(PROVIDER), any(Instant.class));

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_BUSY"));
        verify(providerBookingGuard, times(BookingRequestServiceImpl.MAX_TRANSACTION_ATTEMPTS)).claim(PROVIDER, NOW);
        verify(bookingRepository, never()).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Reference collision: retried with a new reference")
    void referenceCollisionRetries() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-AAAAAA", "BK-20261120-BBBBBB");
        when(bookingRepository.insert(any(Booking.class)))
                .thenThrow(new DuplicateKeyException("E11000 ref_1"))
                .thenAnswer(inv -> {
                    Booking b = inv.getArgument(0);
                    b.setId("booking-3");
                    return b;
                });

        assertThat(enabledService.createBookingRequest(request(PROVIDER)).ref()).isEqualTo("BK-20261120-BBBBBB");
    }

    @Test
    @DisplayName("Transient error detection follows the cause chain")
    void transientDetection() {
        assertThat(BookingRequestServiceImpl.isTransientTransactionError(writeConflict())).isTrue();
        assertThat(BookingRequestServiceImpl.isTransientTransactionError(new RuntimeException("other"))).isFalse();
    }
}
