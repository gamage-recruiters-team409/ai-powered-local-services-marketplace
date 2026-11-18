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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    @DisplayName("assertDisputeEligible (read-only, for the dispute module)")
    class DisputeEligibility {

        @ParameterizedTest(name = "{0} is eligible")
        @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "IN_PROGRESS", "COMPLETION_PENDING"})
        @DisplayName("Open service statuses are eligible; nothing is saved")
        void openStatusesEligible(BookingStatus status) {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(status);

            assertThat(facade.assertDisputeEligible("b-1", CUSTOMER).status()).isEqualTo(status);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("COMPLETED within 72 h: eligible (provider can also check)")
        void completedWithinWindow() {
            loginAs(PROVIDER, Role.PROVIDER);
            givenBooking(bookingReached(BookingStatus.COMPLETED, NOW.minus(Duration.ofHours(71))));

            assertThat(facade.assertDisputeEligible("b-1", PROVIDER).status()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        @DisplayName("COMPLETED after 72 h: 409 DISPUTE_WINDOW_CLOSED")
        void completedAfterWindow() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(bookingReached(BookingStatus.COMPLETED, NOW.minus(Duration.ofHours(73))));

            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", CUSTOMER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "DISPUTE_WINDOW_CLOSED"));
        }

        @Test
        @DisplayName("Already DISPUTED within 72 h of being disputed: eligible")
        void disputedWithinWindow() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(bookingReached(BookingStatus.DISPUTED, NOW.minus(Duration.ofHours(2))));

            assertThat(facade.assertDisputeEligible("b-1", CUSTOMER).status()).isEqualTo(BookingStatus.DISPUTED);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Already DISPUTED more than 72 h ago, or with no timeline time: 409 DISPUTE_WINDOW_CLOSED")
        void disputedAfterWindowOrMissingTime() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(bookingReached(BookingStatus.DISPUTED, NOW.minus(Duration.ofHours(73))));

            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", CUSTOMER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "DISPUTE_WINDOW_CLOSED"));
        }

        @Test
        @DisplayName("DISPUTED with no timeline entry fails closed (409 DISPUTE_WINDOW_CLOSED)")
        void disputedWithoutTimeline() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(BookingStatus.DISPUTED);

            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", CUSTOMER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "DISPUTE_WINDOW_CLOSED"));
        }

        @Test
        @DisplayName("Not yet confirmed (PENDING_PROVIDER): 409 INVALID_STATUS_TRANSITION")
        void pendingNotEligible() {
            loginAs(CUSTOMER, Role.CUSTOMER);
            givenBooking(BookingStatus.PENDING_PROVIDER);

            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", CUSTOMER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
        }

        @Test
        @DisplayName("Non-participant: 403; spoofed actor: 403; not logged in: 401; unknown booking: 404")
        void identityAndNotFound() {
            loginAs(STRANGER, Role.CUSTOMER);
            givenBooking(BookingStatus.CONFIRMED);
            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", STRANGER))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", CUSTOMER))
                    .isInstanceOf(AccessDeniedException.class);

            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> facade.assertDisputeEligible("b-1", CUSTOMER))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertErrorCode(e, "UNAUTHORIZED"));

            loginAs(CUSTOMER, Role.CUSTOMER);
            when(bookingRepository.findById("missing")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> facade.assertDisputeEligible("missing", CUSTOMER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Dispute resolution (admin decision)")
    class DisputeResolution {

        private static final String ADMIN = "a-1";
        private static final String REF = "dispute-d-1";

        /** Booking that already moved DISPUTED → {@code status} (an applied decision). */
        private Booking resolvedTo(BookingStatus status) {
            Booking booking = booking(status);
            booking.getTimeline().add(new BookingStatusChange(BookingStatus.DISPUTED, status,
                    NOW.minus(Duration.ofHours(1)), ADMIN, "Dispute resolved (" + REF + ")"));
            return booking;
        }

        @ParameterizedTest(name = "DISPUTED → {0}")
        @EnumSource(value = BookingStatus.class, names = {"COMPLETED", "REFUNDED", "PARTIALLY_REFUNDED"})
        @DisplayName("Logged-in ADMIN moves a DISPUTED booking to each final status")
        void adminResolves(BookingStatus finalStatus) {
            loginAs(ADMIN, Role.ADMIN);
            insideHttpRequest();
            Booking booking = booking(BookingStatus.DISPUTED);
            givenBooking(booking);
            givenSaveReturnsArgument();

            BookingParticipants result = facade.resolveDispute("b-1", finalStatus, REF);

            assertThat(result.status()).isEqualTo(finalStatus);
            BookingStatusChange change = booking.getTimeline().get(booking.getTimeline().size() - 1);
            assertThat(change.getFromStatus()).isEqualTo(BookingStatus.DISPUTED);
            assertThat(change.getToStatus()).isEqualTo(finalStatus);
            assertThat(change.getChangedBy()).isEqualTo(ADMIN);
            assertThat(change.getReason()).isEqualTo("Dispute resolved (" + REF + ")");
            assertThat(change.getChangedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Internal job (no request, no login) resolves as SYSTEM")
        void internalJobResolvesAsSystem() {
            Booking booking = booking(BookingStatus.DISPUTED);
            givenBooking(booking);
            givenSaveReturnsArgument();

            facade.resolveDispute("b-1", BookingStatus.REFUNDED, REF);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.REFUNDED);
            assertThat(booking.getTimeline().get(booking.getTimeline().size() - 1).getChangedBy())
                    .isEqualTo(BookingStatusChange.SYSTEM);
        }

        @Test
        @DisplayName("HTTP request without login → 401, nothing loaded")
        void requestWithoutLogin() {
            insideHttpRequest();

            assertThatThrownBy(() -> facade.resolveDispute("b-1", BookingStatus.COMPLETED, REF))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
            verifyNoInteractions(bookingRepository);
        }

        @ParameterizedTest(name = "{0} denied")
        @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER", "SUPER_ADMIN", "SUPPORT_ADMIN", "FINANCE_ADMIN", "MODERATOR"})
        @DisplayName("Any role other than ADMIN → 403, nothing loaded")
        void nonAdminDenied(Role role) {
            loginAs(CUSTOMER, role);
            insideHttpRequest();

            assertThatThrownBy(() -> facade.resolveDispute("b-1", BookingStatus.REFUNDED, REF))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(bookingRepository);
        }

        @ParameterizedTest(name = "{0} rejected")
        @EnumSource(value = BookingStatus.class, names = {"COMPLETED", "REFUNDED", "PARTIALLY_REFUNDED"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("A target that is not a final dispute status → 400 INVALID_DISPUTE_RESOLUTION")
        void invalidTarget(BookingStatus target) {
            loginAs(ADMIN, Role.ADMIN);

            assertThatThrownBy(() -> facade.resolveDispute("b-1", target, REF))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertErrorCode(e, "INVALID_DISPUTE_RESOLUTION");
                    });
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Null target → 400 INVALID_DISPUTE_RESOLUTION")
        void nullTarget() {
            loginAs(ADMIN, Role.ADMIN);

            assertThatThrownBy(() -> facade.resolveDispute("b-1", null, REF))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_DISPUTE_RESOLUTION"));
            verifyNoInteractions(bookingRepository);
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "IN_PROGRESS", "COMPLETION_PENDING", "CANCELLED_BY_CUSTOMER"})
        @DisplayName("Booking that is not DISPUTED → 409 INVALID_STATUS_TRANSITION, nothing saved")
        void notDisputed(BookingStatus current) {
            loginAs(ADMIN, Role.ADMIN);
            givenBooking(current);

            assertThatThrownBy(() -> facade.resolveDispute("b-1", BookingStatus.REFUNDED, REF))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("Retry of the same decision → returns the booking unchanged, nothing saved")
        void idempotentRetry() {
            Booking booking = resolvedTo(BookingStatus.PARTIALLY_REFUNDED);
            givenBooking(booking);

            BookingParticipants result = facade.resolveDispute("b-1", BookingStatus.PARTIALLY_REFUNDED, REF);

            assertThat(result.status()).isEqualTo(BookingStatus.PARTIALLY_REFUNDED);
            assertThat(booking.getTimeline()).hasSize(1);
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("Already resolved to a different final status → 409, nothing saved")
        void differentFinalStatus() {
            givenBooking(resolvedTo(BookingStatus.COMPLETED));

            assertThatThrownBy(() -> facade.resolveDispute("b-1", BookingStatus.REFUNDED, REF))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("COMPLETED by the normal flow (not from a dispute) is not treated as a resolved dispute")
        void normalCompletionIsNotAResolution() {
            Booking booking = booking(BookingStatus.COMPLETED);
            booking.getTimeline().add(new BookingStatusChange(BookingStatus.COMPLETION_PENDING,
                    BookingStatus.COMPLETED, NOW.minus(Duration.ofHours(2)), CUSTOMER, null));
            givenBooking(booking);

            assertThatThrownBy(() -> facade.resolveDispute("b-1", BookingStatus.COMPLETED, REF))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("Unknown booking → 404 BOOKING_NOT_FOUND")
        void unknownBooking() {
            when(bookingRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.resolveDispute("missing", BookingStatus.COMPLETED, REF))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Concurrent update → 409 BOOKING_UPDATE_CONFLICT (safe to retry)")
        void concurrentUpdate() {
            givenBooking(BookingStatus.DISPUTED);
            when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("v"));

            assertThatThrownBy(() -> facade.resolveDispute("b-1", BookingStatus.COMPLETED, REF))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertErrorCode(e, "BOOKING_UPDATE_CONFLICT"));
        }

        @Test
        @DisplayName("Blank reference → generic reason; long reference is trimmed to 100 characters")
        void referenceHandling() {
            Booking first = booking(BookingStatus.DISPUTED);
            givenBooking(first);
            givenSaveReturnsArgument();

            facade.resolveDispute("b-1", BookingStatus.COMPLETED, "  ");
            assertThat(first.getTimeline().get(0).getReason()).isEqualTo("Dispute resolved");

            Booking second = booking(BookingStatus.DISPUTED);
            givenBooking(second);
            facade.resolveDispute("b-1", BookingStatus.COMPLETED, "r".repeat(150));
            assertThat(second.getTimeline().get(0).getReason())
                    .isEqualTo("Dispute resolved (" + "r".repeat(100) + ")");
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
