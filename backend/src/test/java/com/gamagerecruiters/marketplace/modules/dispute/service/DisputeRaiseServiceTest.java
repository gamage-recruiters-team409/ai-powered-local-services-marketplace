package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.adapter.BookingFacadeDisputeStatusAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.port.adapter.BookingFacadeLookupAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * POST /disputes service flow with the REAL booking adapters over a mocked BookingFacade, so the facade calls
 * (arguments, order, error pass-through) are exactly what production does.
 */
@ExtendWith(MockitoExtension.class)
class DisputeRaiseServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "cust-1";
    private static final String PROVIDER_ID = "prov-1";
    private static final String DISPUTE_ID = "dispute-1";
    private static final String DESCRIPTION = "Work was not finished, private details here";
    private static final String TIMELINE_REASON = "Dispute raised: POOR_QUALITY";

    @Mock
    private BookingFacade bookingFacade;
    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private PayoutHoldPort payoutHoldPort;

    private DisputeServiceImpl service;

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new DisputeServiceImpl(disputeRepository, new BookingFacadeLookupAdapter(bookingFacade),
                new BookingFacadeDisputeStatusAdapter(bookingFacade), payoutHoldPort, Clock.fixed(NOW, ZoneOffset.UTC));
        serviceLogger = (Logger) LoggerFactory.getLogger(DisputeServiceImpl.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    // ---------- helpers ----------

    private static void loggedInAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void bookingIs(BookingStatus status) {
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenReturn(new BookingParticipants(
                BOOKING_ID, "series-1", "listing-1", CUSTOMER_ID, PROVIDER_ID, status,
                NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
    }

    private void noActiveDispute() {
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(false);
    }

    private void saveReturnsWithId() {
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(invocation -> {
            Dispute dispute = invocation.getArgument(0);
            dispute.setId(DISPUTE_ID);
            return dispute;
        });
    }

    private static RaiseDisputeRequest request() {
        return new RaiseDisputeRequest(BOOKING_ID, DisputeCategory.POOR_QUALITY, "  " + DESCRIPTION + "  ",
                List.of("evidence/photo-1.jpg"));
    }

    private DisputeDetailResponse raiseAs(String userId, Role role) {
        loggedInAs(userId, role);
        return service.raiseDispute(userId, role, request());
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    private void assertNothingSaved() {
        verify(disputeRepository, never()).save(any());
        verifyNoInteractions(payoutHoldPort);
    }

    // ---------- success ----------

    @Test
    @DisplayName("raise: booking moved to DISPUTED first (as the logged-in user), then the dispute is saved with history")
    void raiseSuccess() {
        bookingIs(BookingStatus.COMPLETION_PENDING);
        noActiveDispute();
        saveReturnsWithId();

        DisputeDetailResponse result = raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        InOrder order = inOrder(bookingFacade, disputeRepository, payoutHoldPort);
        order.verify(bookingFacade).getBookingParticipants(BOOKING_ID);
        order.verify(disputeRepository).existsByActiveBookingKey(BOOKING_ID);
        order.verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        order.verify(disputeRepository).save(any(Dispute.class));
        order.verify(payoutHoldPort).holdPayout(BOOKING_ID, DISPUTE_ID);

        assertThat(result.id()).isEqualTo(DISPUTE_ID);
        assertThat(result.status()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
        assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(result.raisedBy()).isEqualTo(CUSTOMER_ID);
        assertThat(result.raisedByRole()).isEqualTo(Role.CUSTOMER);
        assertThat(result.description()).isEqualTo(DESCRIPTION);
        assertThat(result.responseDueAt()).isEqualTo(NOW.plus(DisputeRules.RESPONSE_WINDOW));
        assertThat(result.statusHistory()).singleElement().satisfies(entry -> {
            assertThat(entry.fromStatus()).isNull();
            assertThat(entry.toStatus()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
            assertThat(entry.actorId()).isEqualTo(CUSTOMER_ID);
            assertThat(entry.timestamp()).isEqualTo(NOW);
        });
        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().getActiveBookingKey()).isEqualTo(BOOKING_ID);
    }

    @Test
    @DisplayName("raise: the booking's provider can raise too; the actor sent to the facade is the provider")
    void providerCanRaise() {
        bookingIs(BookingStatus.IN_PROGRESS);
        noActiveDispute();
        saveReturnsWithId();

        assertThat(raiseAs(PROVIDER_ID, Role.PROVIDER).raisedByRole()).isEqualTo(Role.PROVIDER);
        verify(bookingFacade).markDisputed(BOOKING_ID, PROVIDER_ID, TIMELINE_REASON);
    }

    @Test
    @DisplayName("raise: the user's description is never copied into the booking timeline reason")
    void descriptionNotSentToBooking() {
        bookingIs(BookingStatus.CONFIRMED);
        noActiveDispute();
        saveReturnsWithId();

        raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(bookingFacade).markDisputed(eq(BOOKING_ID), eq(CUSTOMER_ID), reason.capture());
        assertThat(reason.getValue()).isEqualTo(TIMELINE_REASON).doesNotContain("private");
    }

    // ---------- already DISPUTED (idempotent) ----------

    @Test
    @DisplayName("raise: booking already DISPUTED (e.g. by 'Raise issue') -> no second markDisputed, dispute is saved")
    void alreadyDisputedSkipsBookingCall() {
        bookingIs(BookingStatus.DISPUTED);
        noActiveDispute();
        saveReturnsWithId();

        DisputeDetailResponse result = raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        assertThat(result.status()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        verify(disputeRepository).save(any(Dispute.class));
    }

    @Test
    @DisplayName("raise: booking already DISPUTED but caller is not a party -> 403, nothing saved, no duplicate lookup")
    void alreadyDisputedStillChecksParty() {
        bookingIs(BookingStatus.DISPUTED);
        loggedInAs("someone-else", Role.CUSTOMER);

        Throwable thrown = catchThrowable(() -> service.raiseDispute("someone-else", Role.CUSTOMER, request()));

        assertThat(thrown).isInstanceOf(AccessDeniedException.class);
        verify(disputeRepository, never()).existsByActiveBookingKey(anyString());
        assertNothingSaved();
    }

    // ---------- the dispute module's own checks ----------

    @Test
    @DisplayName("raise: caller is not the booking's customer or provider -> 403 (same as the facade), booking untouched")
    void nonPartyRejected() {
        bookingIs(BookingStatus.COMPLETION_PENDING);
        loggedInAs("someone-else", Role.CUSTOMER);

        Throwable thrown = catchThrowable(() -> service.raiseDispute("someone-else", Role.CUSTOMER, request()));

        assertThat(thrown).isInstanceOf(AccessDeniedException.class);
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        verify(disputeRepository, never()).existsByActiveBookingKey(anyString());
        assertNothingSaved();
    }

    @Test
    @DisplayName("raise: an active dispute already exists -> 409 DISPUTE_ALREADY_EXISTS, booking untouched")
    void duplicateActiveDispute() {
        bookingIs(BookingStatus.DISPUTED);
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(true);

        Throwable thrown = catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER));

        assertApiError(thrown, HttpStatus.CONFLICT, "DISPUTE_ALREADY_EXISTS");
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        assertNothingSaved();
    }

    @Test
    @DisplayName("raise: concurrent duplicate caught by the unique index on save -> 409 DISPUTE_ALREADY_EXISTS")
    void concurrentDuplicateOnSave() {
        bookingIs(BookingStatus.CONFIRMED);
        noActiveDispute();
        when(disputeRepository.save(any(Dispute.class))).thenThrow(new DuplicateKeyException("dup"));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.CONFLICT,
                "DISPUTE_ALREADY_EXISTS");
        verifyNoInteractions(payoutHoldPort);
    }

    // ---------- facade errors pass through unchanged ----------

    @Test
    @DisplayName("raise: unknown booking -> the facade's 404 BOOKING_NOT_FOUND, same exception, nothing else called")
    void unknownBookingPropagates() {
        ResourceNotFoundException notFound = new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found");
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenThrow(notFound);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(notFound);
        verify(disputeRepository, never()).existsByActiveBookingKey(anyString());
        assertNothingSaved();
    }

    static Stream<Arguments> facadeErrors() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("Access denied: actor does not match the authenticated user")),
                Arguments.of(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")),
                Arguments.of(new ResourceConflictException("INVALID_STATUS_TRANSITION", "Booking cannot move")),
                Arguments.of(new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "The dispute window closes")),
                Arguments.of(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "Updated at the same time")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facadeErrors")
    @DisplayName("raise: markDisputed errors (401, 403, 409 ...) reach the caller unchanged; no dispute is saved")
    void markDisputedErrorsPropagate(RuntimeException facadeError) {
        bookingIs(BookingStatus.COMPLETED);
        noActiveDispute();
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(facadeError);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(facadeError);
        assertNothingSaved();
    }

    // ---------- actor ----------

    @Test
    @DisplayName("raise: the actor passed to markDisputed is always CurrentUser, even if the service got another id")
    void actorIsAlwaysTheLoggedInUser() {
        bookingIs(BookingStatus.CONFIRMED);
        noActiveDispute();
        saveReturnsWithId();
        loggedInAs(CUSTOMER_ID, Role.CUSTOMER);

        // The service is called with the provider's id, but the logged-in user is the customer.
        service.raiseDispute(PROVIDER_ID, Role.PROVIDER, request());

        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        verify(bookingFacade, never()).markDisputed(BOOKING_ID, PROVIDER_ID, TIMELINE_REASON);
    }

    @Test
    @DisplayName("raise: nobody logged in -> the adapter passes no actor, so the facade's own 401 applies")
    void noLoggedInUserPassesNullActor() {
        bookingIs(BookingStatus.CONFIRMED);
        noActiveDispute();
        ApiException unauthorized = new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        when(bookingFacade.markDisputed(BOOKING_ID, null, TIMELINE_REASON)).thenThrow(unauthorized);

        assertThat(catchThrowable(() -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, request())))
                .isSameAs(unauthorized);
        assertNothingSaved();
    }

    // ---------- save failure after the booking change ----------

    @Test
    @DisplayName("raise: save fails after the booking was moved -> structured ERROR (ids only), 503, booking not undone")
    void saveFailureAfterBookingChange() {
        bookingIs(BookingStatus.COMPLETION_PENDING);
        noActiveDispute();
        when(disputeRepository.save(any(Dispute.class)))
                .thenThrow(new DataAccessResourceFailureException("Timed out connecting to db-host.example:27017"));

        Throwable thrown = catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER));

        assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "DISPUTE_SAVE_FAILED");
        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        verifyNoInteractions(payoutHoldPort);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("event=DISPUTE_SAVE_FAILED")
                    .contains("bookingId=" + BOOKING_ID)
                    .contains("actorId=" + CUSTOMER_ID)
                    .contains("bookingMovedToDisputed=true")
                    .contains("cause=DataAccessResourceFailureException")
                    .doesNotContain("db-host").doesNotContain("private");
            assertThat(event.getThrowableProxy()).as("no stack trace with the database message").isNull();
        });
    }

    @Test
    @DisplayName("raise: save fails when the booking was already DISPUTED -> same 503, log says this request did not move it")
    void saveFailureWhenAlreadyDisputed() {
        bookingIs(BookingStatus.DISPUTED);
        noActiveDispute();
        when(disputeRepository.save(any(Dispute.class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE,
                "DISPUTE_SAVE_FAILED");
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("bookingMovedToDisputed=false"));
    }

    @Test
    @DisplayName("raise: a retry after a failed save succeeds without a second markDisputed (booking is now DISPUTED)")
    void retryAfterFailedSaveIsSafe() {
        noActiveDispute();
        // First call: booking not yet disputed; second call: the booking module now reports DISPUTED.
        BookingParticipants pending = new BookingParticipants(BOOKING_ID, "series-1", "listing-1", CUSTOMER_ID,
                PROVIDER_ID, BookingStatus.COMPLETION_PENDING, NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        BookingParticipants disputed = new BookingParticipants(BOOKING_ID, "series-1", "listing-1", CUSTOMER_ID,
                PROVIDER_ID, BookingStatus.DISPUTED, NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenReturn(pending, disputed);
        // First save fails, the retry's save works.
        when(disputeRepository.save(any(Dispute.class)))
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenAnswer(invocation -> {
                    Dispute dispute = invocation.getArgument(0);
                    dispute.setId(DISPUTE_ID);
                    return dispute;
                });

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE,
                "DISPUTE_SAVE_FAILED");

        assertThat(service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, request()).id()).isEqualTo(DISPUTE_ID);
        verify(bookingFacade, org.mockito.Mockito.times(1)).markDisputed(anyString(), anyString(), anyString());
    }
}
