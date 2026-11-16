package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingFacadeImplTest {

    private static final Instant NOW = Instant.parse("2026-12-05T05:00:00Z");
    private static final Instant SLOT_START = Instant.parse("2026-12-01T04:30:00Z");
    private static final Instant SLOT_END = Instant.parse("2026-12-01T06:30:00Z");
    private static final String CUSTOMER = "c-1";
    private static final String PROVIDER = "p-1";
    private static final String STRANGER = "x-9";

    @Mock
    private BookingRepository bookingRepository;

    private BookingFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new BookingFacadeImpl(bookingRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- helpers ----------

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void insideHttpRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static Booking booking(BookingStatus status) {
        Booking booking = new Booking();
        booking.setId("b-1");
        booking.setSeriesId("s-1");
        booking.setListingId("l-1");
        booking.setCustomerId(CUSTOMER);
        booking.setProviderId(PROVIDER);
        booking.setSlotStart(SLOT_START);
        booking.setSlotEnd(SLOT_END);
        booking.setStatus(status);
        return booking;
    }

    /** Booking whose timeline says it reached {@code status} at {@code at}. */
    private static Booking bookingReached(BookingStatus status, Instant at) {
        Booking booking = booking(status);
        booking.getTimeline().add(new BookingStatusChange(null, status, at, "someone", null));
        return booking;
    }

    private void givenBooking(Booking booking) {
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking));
    }

    private void givenBooking(BookingStatus status) {
        givenBooking(booking(status));
    }

    private void givenSaveReturnsArgument() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static void assertErrorCode(Throwable e, String code) {
        assertThat(((ApiException) e).getErrorCode()).isEqualTo(code);
    }

    @Nested
    @DisplayName("Read methods")
    class ReadMethods {

        @Test
        @DisplayName("hasOpenBookingsForCustomer checks the six open statuses")
        void hasOpenBookingsUsesOpenStatuses() {
            when(bookingRepository.existsByCustomerIdAndStatusIn(CUSTOMER, BookingStatus.OPEN_STATUSES)).thenReturn(true);

            assertThat(facade.hasOpenBookingsForCustomer(CUSTOMER)).isTrue();
        }

        @Test
        @DisplayName("hasOpenBookingsForCustomer returns false for a blank customer ID without querying")
        void hasOpenBookingsBlankId() {
            assertThat(facade.hasOpenBookingsForCustomer(" ")).isFalse();
            assertThat(facade.hasOpenBookingsForCustomer(null)).isFalse();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("getBookingParticipants returns IDs, status and the UTC time slot")
        void getParticipants() {
            givenBooking(BookingStatus.CONFIRMED);

            assertThat(facade.getBookingParticipants("b-1")).isEqualTo(new BookingParticipants(
                    "b-1", "s-1", "l-1", CUSTOMER, PROVIDER, BookingStatus.CONFIRMED, SLOT_START, SLOT_END));
        }

        @Test
        @DisplayName("getBookingParticipants throws BOOKING_NOT_FOUND for an unknown or blank ID")
        void getParticipantsNotFound() {
            when(bookingRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.getBookingParticipants("missing"))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> facade.getBookingParticipants(""))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Authenticated identity binding")
    class IdentityBinding {

        @Test
        @DisplayName("No authenticated user: 401 UNAUTHORIZED, booking not loaded")
        void missingAuthentication() {
            assertThatThrownBy(() -> facade.markInProgress("b-1", PROVIDER))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        assertErrorCode(e, "UNAUTHORIZED");
                        assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Spoofed actor: logged in as the customer but passing the provider's ID → 403")
        void spoofedActorId() {
            loginAs(CUSTOMER, Role.CUSTOMER);

            assertThatThrownBy(() -> facade.markInProgress("b-1", PROVIDER))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Passing \"SYSTEM\" as actor to a user method is rejected (403)")
        void systemStringAsActorRejected() {
            loginAs(CUSTOMER, Role.CUSTOMER);

            assertThatThrownBy(() -> facade.markCompleted("b-1", "SYSTEM"))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Authenticated user who is not the booking's provider → 403, nothing saved")
        void authenticatedButNotParticipant() {
            loginAs(STRANGER, Role.PROVIDER);
            givenBooking(BookingStatus.CONFIRMED);

            assertThatThrownBy(() -> facade.markInProgress("b-1", STRANGER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Provider transitions")
    class ProviderTransitions {

        @Test
        @DisplayName("Provider moves CONFIRMED to IN_PROGRESS; timeline records the provider")
        void providerStartsService() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(BookingStatus.CONFIRMED);
            givenSaveReturnsArgument();

            BookingParticipants result = facade.markInProgress("b-1", PROVIDER);

            assertThat(result.status()).isEqualTo(BookingStatus.IN_PROGRESS);
            verify(bookingRepository).save(argThat(saved ->
                    saved.getTimeline().size() == 1
                            && PROVIDER.equals(saved.getTimeline().get(0).getChangedBy())
                            && NOW.equals(saved.getTimeline().get(0).getChangedAt())));
        }

        @Test
        @DisplayName("Provider moves IN_PROGRESS to COMPLETION_PENDING")
        void providerMarksCompletionPending() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(BookingStatus.IN_PROGRESS);
            givenSaveReturnsArgument();

            assertThat(facade.markCompletionPending("b-1", PROVIDER).status())
                    .isEqualTo(BookingStatus.COMPLETION_PENDING);
        }

        @Test
        @DisplayName("Customer cannot start the service (403)")
        void customerCannotStart() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(BookingStatus.CONFIRMED);

            assertThatThrownBy(() -> facade.markInProgress("b-1", CUSTOMER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Wrong starting status: 409 INVALID_STATUS_TRANSITION")
        void invalidStartingStatus() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(BookingStatus.PENDING_PROVIDER);

            assertThatThrownBy(() -> facade.markInProgress("b-1", PROVIDER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markCompleted (customer)")
    class CustomerCompletion {

        @Test
        @DisplayName("Customer confirms COMPLETION_PENDING → COMPLETED")
        void customerConfirms() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(BookingStatus.COMPLETION_PENDING);
            givenSaveReturnsArgument();

            assertThat(facade.markCompleted("b-1", CUSTOMER).status()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        @DisplayName("Customer cannot resolve a DISPUTED booking through markCompleted (409)")
        void customerCannotResolveDispute() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(BookingStatus.DISPUTED);

            assertThatThrownBy(() -> facade.markCompleted("b-1", CUSTOMER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Provider cannot confirm completion (403)")
        void providerCannotConfirm() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(BookingStatus.COMPLETION_PENDING);

            assertThatThrownBy(() -> facade.markCompleted("b-1", PROVIDER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markCompletedBySystem (48 h auto-confirm, internal jobs only)")
    class SystemCompletion {

        @Test
        @DisplayName("Internal job after 48 h: COMPLETION_PENDING → COMPLETED recorded as SYSTEM")
        void systemCompletesWhenDue() {
            givenBooking(bookingReached(BookingStatus.COMPLETION_PENDING, NOW.minus(Duration.ofHours(48))));
            givenSaveReturnsArgument();

            BookingParticipants result = facade.markCompletedBySystem("b-1");

            assertThat(result.status()).isEqualTo(BookingStatus.COMPLETED);
            verify(bookingRepository).save(argThat(saved -> BookingStatusChange.SYSTEM.equals(
                    saved.getTimeline().get(saved.getTimeline().size() - 1).getChangedBy())));
        }

        @Test
        @DisplayName("Before 48 h: 409 AUTO_COMPLETION_NOT_DUE")
        void systemRejectedBeforeDue() {
            givenBooking(bookingReached(BookingStatus.COMPLETION_PENDING, NOW.minus(Duration.ofHours(47))));

            assertThatThrownBy(() -> facade.markCompletedBySystem("b-1"))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "AUTO_COMPLETION_NOT_DUE"));
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Called during an HTTP request: 403, booking not loaded")
        void systemRejectedInsideHttpRequest() {
            insideHttpRequest();

            assertThatThrownBy(() -> facade.markCompletedBySystem("b-1"))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Called with a logged-in user: 403, booking not loaded")
        void systemRejectedWithAuthenticatedUser() {
            loginAs(CUSTOMER, Role.CUSTOMER);

            assertThatThrownBy(() -> facade.markCompletedBySystem("b-1"))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("SYSTEM cannot resolve a DISPUTED booking (409)")
        void systemCannotResolveDispute() {
            givenBooking(bookingReached(BookingStatus.DISPUTED, NOW.minus(Duration.ofDays(10))));

            assertThatThrownBy(() -> facade.markCompletedBySystem("b-1"))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markDisputed (customer or provider)")
    class DisputeTransitions {

        @Test
        @DisplayName("Customer raises an issue from COMPLETION_PENDING; reason stored trimmed")
        void customerDisputes() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(BookingStatus.COMPLETION_PENDING);
            givenSaveReturnsArgument();

            assertThat(facade.markDisputed("b-1", CUSTOMER, "  Work not finished ").status())
                    .isEqualTo(BookingStatus.DISPUTED);
            verify(bookingRepository).save(argThat(saved ->
                    "Work not finished".equals(saved.getTimeline().get(0).getReason())));
        }

        @Test
        @DisplayName("Provider can open a dispute from CONFIRMED (e.g. no-show)")
        void providerDisputes() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(BookingStatus.CONFIRMED);
            givenSaveReturnsArgument();

            assertThat(facade.markDisputed("b-1", PROVIDER, "NO_SHOW_BY_CUSTOMER").status())
                    .isEqualTo(BookingStatus.DISPUTED);
        }

        @Test
        @DisplayName("After completion, within 72 h: allowed (BR-008)")
        void disputeWithinWindow() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(bookingReached(BookingStatus.COMPLETED, NOW.minus(Duration.ofHours(71))));
            givenSaveReturnsArgument();

            assertThat(facade.markDisputed("b-1", CUSTOMER, "Issue found").status())
                    .isEqualTo(BookingStatus.DISPUTED);
        }

        @Test
        @DisplayName("After completion, after 72 h: 409 DISPUTE_WINDOW_CLOSED")
        void disputeAfterWindow() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(bookingReached(BookingStatus.COMPLETED, NOW.minus(Duration.ofHours(73))));

            assertThatThrownBy(() -> facade.markDisputed("b-1", CUSTOMER, "Too late"))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "DISPUTE_WINDOW_CLOSED"));
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("A non-participant cannot open a dispute (403)")
        void strangerDenied() {
            loginAs(STRANGER, Role.CUSTOMER);
            givenBooking(BookingStatus.COMPLETION_PENDING);

            assertThatThrownBy(() -> facade.markDisputed("b-1", STRANGER, "x"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Common error cases")
    class CommonErrors {

        @Test
        @DisplayName("Unknown booking: 404 BOOKING_NOT_FOUND")
        void unknownBooking() {
            loginAs(PROVIDER, Role.PROVIDER);
            when(bookingRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.markInProgress("missing", PROVIDER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Concurrent change (optimistic lock): 409 BOOKING_UPDATE_CONFLICT")
        void optimisticLockConflict() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(BookingStatus.CONFIRMED);
            when(bookingRepository.save(any(Booking.class)))
                    .thenThrow(new OptimisticLockingFailureException("version mismatch"));

            assertThatThrownBy(() -> facade.markInProgress("b-1", PROVIDER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "BOOKING_UPDATE_CONFLICT"));
        }
    }
}
