package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDecisionResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.DeclineBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@ExtendWith(MockitoExtension.class)
class ProviderBookingDecisionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ProviderVerificationFacade providerVerification;

    @Mock
    private BookingNotifier notifier;

    private ProviderBookingDecisionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProviderBookingDecisionServiceImpl(bookingRepository, providerVerification,
                new BookingPaymentPolicy(15, 15), notifier, Clock.fixed(NOW, ZoneOffset.UTC));
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

    private Booking pending() {
        Booking booking = new Booking();
        booking.setId("b-1");
        booking.setRef("BK-1");
        booking.setCustomerId("cust-1");
        booking.setProviderId(PROVIDER);
        booking.setStatus(BookingStatus.PENDING_PROVIDER);
        booking.setResponseDeadline(NOW.plusSeconds(3600));
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking));
        return booking;
    }

    private void givenSaveReturnsArgument() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Accept: PENDING_PROVIDER → AWAITING_PAYMENT, acceptedAt now, payment due in 15 minutes")
    void accept() {
        loginAs(PROVIDER, Role.PROVIDER);
        Booking booking = pending();
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
        givenSaveReturnsArgument();

        BookingDecisionResponse response = service.accept("b-1");

        assertThat(response.status()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(response.acceptedAt()).isEqualTo(NOW);
        assertThat(response.paymentDueAt()).isEqualTo(NOW.plusSeconds(15 * 60));
        assertThat(booking.getTimeline()).hasSize(1);
        assertThat(booking.getTimeline().get(0).getChangedBy()).isEqualTo(PROVIDER);
        verify(notifier).accepted(booking);
    }

    @Test
    @DisplayName("Accept: provider no longer APPROVED (paused/expired) → 409 PROVIDER_NOT_BOOKABLE, nothing saved")
    void acceptNotApproved() {
        loginAs(PROVIDER, Role.PROVIDER);
        pending();
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(false);

        assertThatThrownBy(() -> service.accept("b-1"))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("PROVIDER_NOT_BOOKABLE"));
        verify(bookingRepository, never()).save(any(Booking.class));
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Another provider → 403, nothing saved")
    void notOwner() {
        loginAs("prov-2", Role.PROVIDER);
        pending();

        assertThatThrownBy(() -> service.accept("b-1")).isInstanceOf(AccessDeniedException.class);
        verify(bookingRepository, never()).save(any(Booking.class));
        verifyNoInteractions(providerVerification);
    }

    @Test
    @DisplayName("Not logged in → 401")
    void unauthenticated() {
        assertThatThrownBy(() -> service.accept("b-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("UNAUTHORIZED"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Unknown booking → 404")
    void unknown() {
        loginAs(PROVIDER, Role.PROVIDER);
        when(bookingRepository.findById("x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept("x")).isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = BookingStatus.class, names = {"AWAITING_PAYMENT", "CONFIRMED", "DECLINED", "EXPIRED", "CANCELLED_BY_CUSTOMER"})
    @DisplayName("Not PENDING_PROVIDER → 409 INVALID_STATUS_TRANSITION")
    void notPending(BookingStatus status) {
        loginAs(PROVIDER, Role.PROVIDER);
        pending().setStatus(status);

        assertThatThrownBy(() -> service.accept("b-1"))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("INVALID_STATUS_TRANSITION"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Response deadline passed → 409 RESPONSE_DEADLINE_PASSED (expiry job handles it)")
    void deadlinePassed() {
        loginAs(PROVIDER, Role.PROVIDER);
        pending().setResponseDeadline(NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.accept("b-1"))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("RESPONSE_DEADLINE_PASSED"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Concurrent update → 409 BOOKING_UPDATE_CONFLICT")
    void concurrentUpdate() {
        loginAs(PROVIDER, Role.PROVIDER);
        pending();
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("v"));

        assertThatThrownBy(() -> service.accept("b-1"))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_UPDATE_CONFLICT"));
    }

    @Test
    @DisplayName("Decline with a dropdown reason → DECLINED, reason label in the timeline")
    void decline() {
        loginAs(PROVIDER, Role.PROVIDER);
        Booking booking = pending();
        givenSaveReturnsArgument();

        BookingDecisionResponse response = service.decline("b-1",
                new DeclineBookingRequest(BookingDeclineReason.NOT_AVAILABLE, null));

        assertThat(response.status()).isEqualTo(BookingStatus.DECLINED);
        assertThat(booking.getTimeline().get(0).getReason()).isEqualTo("Declined: Not available at this time");
        verifyNoInteractions(providerVerification);
        verify(notifier).declined(booking, BookingDeclineReason.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("Decline with OTHER and a note → DECLINED, note in the timeline")
    void declineOther() {
        loginAs(PROVIDER, Role.PROVIDER);
        Booking booking = pending();
        givenSaveReturnsArgument();

        service.decline("b-1", new DeclineBookingRequest(BookingDeclineReason.OTHER, "  Vehicle broke down "));

        assertThat(booking.getTimeline().get(0).getReason()).isEqualTo("Declined: Other - Vehicle broke down");
    }

    @Test
    @DisplayName("OTHER without a note → 400 DECLINE_NOTE_REQUIRED, nothing loaded")
    void otherNeedsNote() {
        loginAs(PROVIDER, Role.PROVIDER);

        assertThatThrownBy(() -> service.decline("b-1", new DeclineBookingRequest(BookingDeclineReason.OTHER, " ")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("DECLINE_NOTE_REQUIRED"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("A note with a non-OTHER reason → 400 DECLINE_NOTE_NOT_ALLOWED (C-09: text only for Other)")
    void noteOnlyForOther() {
        loginAs(PROVIDER, Role.PROVIDER);

        assertThatThrownBy(() -> service.decline("b-1",
                new DeclineBookingRequest(BookingDeclineReason.NOT_AVAILABLE, "busy")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("DECLINE_NOTE_NOT_ALLOWED"));
        verifyNoInteractions(bookingRepository);
    }
}
