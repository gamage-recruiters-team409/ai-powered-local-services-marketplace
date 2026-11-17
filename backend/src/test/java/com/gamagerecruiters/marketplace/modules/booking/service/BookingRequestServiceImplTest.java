package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
    private ProviderBookingLock providerBookingLock;

    @Mock
    private BookingReferenceGenerator referenceGenerator;

    private BookingRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingRequestServiceImpl(bookingRepository, slotService, providerBookingLock,
                referenceGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
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

    private void givenLockAcquired() {
        when(providerBookingLock.tryAcquire(PROVIDER)).thenReturn("owner-1");
    }

    private void givenInsertAssignsId() {
        when(bookingRepository.insert(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId("booking-1");
            return b;
        });
    }

    @Test
    @DisplayName("Creates a PENDING_PROVIDER booking for the logged-in customer with ref, deadline, address and timeline")
    void createsBooking() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenLockAcquired();
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-ABCDEF");
        givenInsertAssignsId();

        BookingResponse response = service.createBookingRequest(request(PROVIDER));

        assertThat(response.id()).isEqualTo("booking-1");
        assertThat(response.ref()).isEqualTo("BK-20261120-ABCDEF");
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        assertThat(response.customerId()).isEqualTo(CUSTOMER);
        assertThat(response.slotEnd()).isEqualTo(Instant.parse("2026-11-23T04:30:00Z"));
        assertThat(response.localDate()).isEqualTo("2026-11-23");
        assertThat(response.localStart()).isEqualTo("09:00");
        assertThat(response.localEnd()).isEqualTo("10:00");
        // 12 h after the request is earlier than 1 h before the start
        assertThat(response.responseDeadline()).isEqualTo(Instant.parse("2026-11-20T14:30:00Z"));
        assertThat(response.address().line1()).isEqualTo("12 Temple Road");
        assertThat(response.address().district()).isEqualTo("Colombo");
        assertThat(response.notes()).isEqualTo("Gate code 1234");

        verify(bookingRepository).insert(argThat((Booking b) ->
                b.getTimeline().size() == 1
                        && b.getTimeline().get(0).getToStatus() == BookingStatus.PENDING_PROVIDER
                        && CUSTOMER.equals(b.getTimeline().get(0).getChangedBy())));
        verify(providerBookingLock).release(PROVIDER, "owner-1");
    }

    @Test
    @DisplayName("Response deadline (C-02) is 1 h before the start when that is earlier than 12 h")
    void responseDeadlineBeforeStart() {
        Instant requestedAt = Instant.parse("2026-11-20T02:30:00Z");
        Instant startIn5Hours = Instant.parse("2026-11-20T07:30:00Z");

        assertThat(BookingRequestServiceImpl.responseDeadline(requestedAt, startIn5Hours))
                .isEqualTo(Instant.parse("2026-11-20T06:30:00Z"));
    }

    @Test
    @DisplayName("Not logged in: 401 UNAUTHORIZED, nothing locked or saved")
    void unauthenticated() {
        assertThatThrownBy(() -> service.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(providerBookingLock, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Booking yourself: 400 SELF_BOOKING_NOT_ALLOWED")
    void selfBooking() {
        loginAs(PROVIDER, Role.CUSTOMER);

        assertThatThrownBy(() -> service.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SELF_BOOKING_NOT_ALLOWED"));
        verifyNoInteractions(providerBookingLock, bookingRepository);
    }

    @Test
    @DisplayName("Slot no longer available: 409 SLOT_UNAVAILABLE, nothing saved, lock released")
    void slotUnavailable() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenLockAcquired();
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(false);

        assertThatThrownBy(() -> service.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SLOT_UNAVAILABLE"));
        verify(bookingRepository, never()).insert(any(Booking.class));
        verify(providerBookingLock).release(PROVIDER, "owner-1");
    }

    @Test
    @DisplayName("Provider lock held by another request for 2 s: 409 BOOKING_BUSY, no slot check")
    void lockBusy() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(providerBookingLock.tryAcquire(PROVIDER)).thenReturn(null);

        assertThatThrownBy(() -> service.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_BUSY"));
        verify(providerBookingLock, times(BookingRequestServiceImpl.LOCK_ATTEMPTS)).tryAcquire(PROVIDER);
        verifyNoInteractions(slotService, bookingRepository);
    }

    @Test
    @DisplayName("Reference collision: retries with a new reference")
    void referenceCollisionRetries() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenLockAcquired();
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-AAAAAA", "BK-20261120-BBBBBB");
        when(bookingRepository.insert(any(Booking.class)))
                .thenThrow(new DuplicateKeyException("E11000 ref_1"))
                .thenAnswer(inv -> {
                    Booking b = inv.getArgument(0);
                    b.setId("booking-2");
                    return b;
                });

        BookingResponse response = service.createBookingRequest(request(PROVIDER));

        assertThat(response.ref()).isEqualTo("BK-20261120-BBBBBB");
        verify(bookingRepository, times(2)).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Lock is released even when saving fails")
    void lockReleasedOnFailure() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenLockAcquired();
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(any(Instant.class))).thenReturn("BK-1", "BK-2", "BK-3");
        when(bookingRepository.insert(any(Booking.class))).thenThrow(new DuplicateKeyException("E11000 ref_1"));

        assertThatThrownBy(() -> service.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(IllegalStateException.class);
        verify(providerBookingLock).release(eq(PROVIDER), anyString());
    }
}
