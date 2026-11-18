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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.adapter.BookingFacadeDisputeStatusAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.port.adapter.BookingFacadeLookupAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * POST /disputes service flow. The eligibility port is mocked: it stands in for the Booking module's
 * BookingFacade.assertDisputeEligible (being merged by the booking owner), so the whole flow is tested against the
 * agreed contract. markDisputed goes through the REAL adapter over a mocked BookingFacade.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeRaiseServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "cust-1";
    private static final String PROVIDER_ID = "prov-1";
    private static final String DISPUTE_ID = "dispute-1";
    private static final String DESCRIPTION = "Work was not finished, private details here";
    private static final String TIMELINE_REASON = "Dispute raised: POOR_QUALITY";
    private static final String RETRY_OWNER = "retry-lease-token";

    @Mock
    private BookingFacade bookingFacade;
    @Mock
    private DisputeEligibilityPort eligibilityPort;
    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private DisputeBookingSyncStore syncStore;
    @Mock
    private PayoutHoldPort payoutHoldPort;

    private DisputeServiceImpl service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new DisputeServiceImpl(disputeRepository, syncStore, new BookingFacadeDisputeStatusAdapter(bookingFacade),
                new BookingFacadeLookupAdapter(bookingFacade), eligibilityPort, payoutHoldPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
        serviceLogger = (Logger) LoggerFactory.getLogger(DisputeServiceImpl.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
        // Defaults: eligible, not yet DISPUTED; no active dispute; insert assigns an id; markSynced succeeds.
        eligible(false);
        bookingReReadAs(BookingStatus.CONFIRMED);
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID)).thenReturn(Optional.empty());
        when(disputeRepository.insert(any(Dispute.class))).thenAnswer(invocation -> {
            Dispute dispute = invocation.getArgument(0);
            dispute.setId(DISPUTE_ID);
            return dispute;
        });
        when(syncStore.markSynced(eq(DISPUTE_ID), anyString())).thenAnswer(invocation -> Optional.of(dispute(CUSTOMER_ID, BookingSyncState.SYNCED)));
        when(syncStore.claimById(eq(DISPUTE_ID), eq(NOW), any(Duration.class))).thenAnswer(invocation -> {
            Dispute claimed = dispute(CUSTOMER_ID, BookingSyncState.PENDING);
            claimed.setSyncLeaseOwner(RETRY_OWNER);
            return Optional.of(claimed);
        });
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

    /** What a re-read of the booking (BookingFacade.getBookingParticipants) returns. */
    private void bookingReReadAs(BookingStatus status) {
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenReturn(new BookingParticipants(
                BOOKING_ID, "series-1", "listing-1", CUSTOMER_ID, PROVIDER_ID, status, NOW, NOW.plusSeconds(3600)));
    }

    /** What BookingFacade.assertDisputeEligible returns for an eligible booking. */
    private void eligible(boolean alreadyDisputed) {
        when(eligibilityPort.assertEligible(BOOKING_ID))
                .thenReturn(new BookingSnapshot(BOOKING_ID, CUSTOMER_ID, PROVIDER_ID, alreadyDisputed));
    }

    private static RaiseDisputeRequest request() {
        return new RaiseDisputeRequest(BOOKING_ID, DisputeCategory.POOR_QUALITY, "  " + DESCRIPTION + "  ",
                List.of("evidence/photo-1.jpg"));
    }

    private static Dispute dispute(String raisedBy, BookingSyncState state) {
        Dispute dispute = new Dispute();
        dispute.setId(DISPUTE_ID);
        dispute.setBookingId(BOOKING_ID);
        dispute.setCustomerId(CUSTOMER_ID);
        dispute.setProviderId(PROVIDER_ID);
        dispute.setRaisedBy(raisedBy);
        dispute.setRaisedByRole(Role.CUSTOMER);
        dispute.setCategory(DisputeCategory.POOR_QUALITY);
        dispute.setDescription(DESCRIPTION);
        dispute.setCreatedAt(NOW);
        dispute.setResponseDueAt(NOW.plus(DisputeRules.RESPONSE_WINDOW));
        dispute.setBookingSyncState(state);
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, raisedBy, Role.CUSTOMER, null, NOW);
        return dispute;
    }

    private DisputeDetailResponse raiseAs(String userId, Role role) {
        loggedInAs(userId, role);
        return service.raiseDispute(userId, role, request());
    }

    private Dispute insertedDispute() {
        ArgumentCaptor<Dispute> captor = ArgumentCaptor.forClass(Dispute.class);
        verify(disputeRepository).insert(captor.capture());
        return captor.getValue();
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    private void assertNoBookingChangeAndNothingSaved() {
        verify(bookingFacade, never()).markDisputed(anyString(), any(), anyString());
        verify(disputeRepository, never()).insert(any(Dispute.class));
        verifyNoInteractions(payoutHoldPort);
    }

    private List<String> errorLogs() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---------- booking not yet DISPUTED ----------

    @Test
    @DisplayName("raise: eligibility -> duplicate check -> PENDING insert -> markDisputed as the logged-in user -> SYNCED")
    void raiseNotYetDisputed() {
        DisputeDetailResponse result = raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        InOrder order = inOrder(eligibilityPort, disputeRepository, bookingFacade, syncStore, payoutHoldPort);
        order.verify(eligibilityPort).assertEligible(BOOKING_ID);
        order.verify(disputeRepository).findByActiveBookingKey(BOOKING_ID);
        order.verify(disputeRepository).insert(any(Dispute.class));
        order.verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        order.verify(syncStore).markSynced(eq(DISPUTE_ID), anyString());
        order.verify(payoutHoldPort).holdPayout(BOOKING_ID, DISPUTE_ID);

        Dispute intent = insertedDispute();
        assertThat(intent.getBookingSyncState()).isEqualTo(BookingSyncState.PENDING);
        assertThat(intent.getActiveBookingKey()).as("holds the unique active-booking key").isEqualTo(BOOKING_ID);
        assertThat(intent.getRaisedBy()).isEqualTo(CUSTOMER_ID);
        assertThat(intent.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(intent.getProviderId()).isEqualTo(PROVIDER_ID);
        assertThat(intent.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(intent.getSyncLeaseOwner()).as("inserted already leased to this request").isNotBlank();
        assertThat(intent.getSyncLeaseUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(result.id()).isEqualTo(DISPUTE_ID);
        assertThat(result.status()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        verify(syncStore, never()).claimById(anyString(), any(), any());
    }

    @Test
    @DisplayName("raise: the provider can raise too; the user's description is never sent to the booking timeline")
    void providerCanRaise() {
        raiseAs(PROVIDER_ID, Role.PROVIDER);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(bookingFacade).markDisputed(eq(BOOKING_ID), eq(PROVIDER_ID), reason.capture());
        assertThat(reason.getValue()).isEqualTo(TIMELINE_REASON).doesNotContain("private");
    }

    static Stream<Arguments> facadeErrors() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("Access denied: actor does not match the authenticated user")),
                Arguments.of(new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found")),
                Arguments.of(new ResourceConflictException("INVALID_STATUS_TRANSITION", "Booking cannot move")),
                Arguments.of(new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "The dispute window closes")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facadeErrors")
    @DisplayName("raise: CONFIRMED rejection and the re-read booking is not DISPUTED -> the PENDING record is deleted "
            + "with this request's lease token and the facade error returned unchanged")
    void markDisputedRejectedDeletesIntent(RuntimeException facadeError) {
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(facadeError);
        when(syncStore.deleteIfPending(eq(DISPUTE_ID), anyString())).thenReturn(true);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(facadeError);

        InOrder order = inOrder(bookingFacade, syncStore);
        order.verify(bookingFacade).getBookingParticipants(BOOKING_ID);
        String owner = insertedDispute().getSyncLeaseOwner();
        order.verify(syncStore).deleteIfPending(DISPUTE_ID, owner);
        verify(syncStore, never()).markSynced(anyString(), anyString());
        verify(syncStore, never()).releaseLease(anyString(), anyString());
        verifyNoInteractions(payoutHoldPort);
    }

    static Stream<Arguments> uncertainErrors() {
        return Stream.of(
                Arguments.of(new QueryTimeoutException("socket timeout to db-host:27017")),
                Arguments.of(new DataAccessResourceFailureException("db-host:27017 unreachable")),
                Arguments.of(new IllegalStateException("unexpected")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uncertainErrors")
    @DisplayName("raise: markDisputed fails with an UNCERTAIN outcome -> record KEPT (never deleted), lease released, "
            + "503 DISPUTE_SYNC_PENDING; the reconciler settles it from the booking's real state")
    void uncertainBookingUpdateKeepsIntent(RuntimeException facadeError) {
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(facadeError);

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE,
                "DISPUTE_SYNC_PENDING");

        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        verify(syncStore, never()).markSynced(anyString(), anyString());
        String owner = insertedDispute().getSyncLeaseOwner();
        verify(syncStore).releaseLease(DISPUTE_ID, owner);
        verifyNoInteractions(payoutHoldPort);
        assertThat(errorLogs()).singleElement().asString()
                .contains("event=DISPUTE_BOOKING_UPDATE_UNCERTAIN").contains("disputeId=" + DISPUTE_ID)
                .doesNotContain("db-host").doesNotContain("private");
    }

    static Stream<Arguments> keptBookingAnswers() {
        return Stream.of(
                Arguments.of(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")),
                Arguments.of(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "Updated at the same time")),
                Arguments.of(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM", "unavailable")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keptBookingAnswers")
    @DisplayName("raise: a Booking-module error that is NOT a confirmed rejection -> record KEPT (never deleted), lease "
            + "released, the error returned unchanged")
    void unconfirmedBookingAnswerKeepsIntent(RuntimeException facadeError) {
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(facadeError);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(facadeError);

        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        verify(syncStore, never()).markSynced(anyString(), anyString());
        String owner = insertedDispute().getSyncLeaseOwner();
        verify(syncStore).releaseLease(DISPUTE_ID, owner);
        verifyNoInteractions(payoutHoldPort);
    }

    @Test
    @DisplayName("raise: confirmed rejection but the re-read booking IS DISPUTED -> NOT deleted; the dispute is completed")
    void rejectedButBookingDisputedCompletesDispute() {
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON))
                .thenThrow(new ResourceConflictException("INVALID_STATUS_TRANSITION", "Booking cannot move from DISPUTED"));
        bookingReReadAs(BookingStatus.DISPUTED);

        assertThat(raiseAs(CUSTOMER_ID, Role.CUSTOMER).id()).isEqualTo(DISPUTE_ID);

        String owner = insertedDispute().getSyncLeaseOwner();
        verify(syncStore).markSynced(DISPUTE_ID, owner);
        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        verify(payoutHoldPort).holdPayout(BOOKING_ID, DISPUTE_ID);
    }

    @Test
    @DisplayName("raise: confirmed rejection but the booking cannot be re-read -> record KEPT, facade error unchanged")
    void rejectedButReReadFailsKeepsIntent() {
        ResourceConflictException closed = new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "closed");
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(closed);
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenThrow(new DataAccessResourceFailureException("db-host"));

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(closed);

        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        String owner = insertedDispute().getSyncLeaseOwner();
        verify(syncStore).releaseLease(DISPUTE_ID, owner);
    }

    @Test
    @DisplayName("classification: only 404, 403, 409 INVALID_STATUS_TRANSITION and 409 DISPUTE_WINDOW_CLOSED are confirmed")
    void confirmedRejectionClassification() {
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ResourceConflictException("INVALID_STATUS_TRANSITION", "x"))).isTrue();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "x"))).isTrue();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ResourceNotFoundException("BOOKING_NOT_FOUND", "x"))).isTrue();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new AccessDeniedException("x"))).isTrue();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "x"))).isTrue();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "x"))).isFalse();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "x"))).isFalse();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "X", "x"))).isFalse();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new QueryTimeoutException("x"))).isFalse();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new DataAccessResourceFailureException("x"))).isFalse();
        assertThat(DisputeServiceImpl.isConfirmedRejection(new RuntimeException("x"))).isFalse();
    }

    @Test
    @DisplayName("raise: if even the compensation delete fails, the facade error is still returned and logged by ids")
    void compensationFailureStillReturnsFacadeError() {
        ResourceConflictException rejected = new ResourceConflictException("INVALID_STATUS_TRANSITION", "no");
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(rejected);
        when(syncStore.deleteIfPending(eq(DISPUTE_ID), anyString())).thenThrow(new DataAccessResourceFailureException("db-host:27017"));

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(rejected);

        assertThat(errorLogs()).singleElement().asString()
                .contains("event=DISPUTE_INTENT_COMPENSATION_FAILED").contains("disputeId=" + DISPUTE_ID)
                .contains("bookingId=" + BOOKING_ID).doesNotContain("db-host");
    }

    @Test
    @DisplayName("raise: the SYNCED update fails after the booking moved -> 503 DISPUTE_SYNC_PENDING, record kept PENDING")
    void syncFailureKeepsIntentForReconciler() {
        when(syncStore.markSynced(eq(DISPUTE_ID), anyString())).thenThrow(new DataAccessResourceFailureException("db-host:27017"));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE,
                "DISPUTE_SYNC_PENDING");

        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        String owner = insertedDispute().getSyncLeaseOwner();
        verify(syncStore).releaseLease(DISPUTE_ID, owner);
        verifyNoInteractions(payoutHoldPort);
        assertThat(errorLogs()).singleElement().asString()
                .contains("event=DISPUTE_SYNC_PENDING").contains("disputeId=" + DISPUTE_ID)
                .contains("actorId=" + CUSTOMER_ID).doesNotContain("db-host").doesNotContain("private");
    }

    @Test
    @DisplayName("raise: the reconciler completed the record first -> the visible dispute is returned")
    void syncAlreadyCompletedByReconciler() {
        when(syncStore.markSynced(eq(DISPUTE_ID), anyString())).thenReturn(Optional.empty());
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(CUSTOMER_ID, BookingSyncState.SYNCED)));

        assertThat(raiseAs(CUSTOMER_ID, Role.CUSTOMER).id()).isEqualTo(DISPUTE_ID);
    }

    @Test
    @DisplayName("raise: the PENDING insert itself fails -> 503 DISPUTE_SAVE_FAILED and the booking is never touched")
    void insertFailureLeavesBookingUnchanged() {
        when(disputeRepository.insert(any(Dispute.class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE,
                "DISPUTE_SAVE_FAILED");
        verify(bookingFacade, never()).markDisputed(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("raise: a concurrent raise holds the unique key -> 409 DISPUTE_ALREADY_EXISTS, booking untouched")
    void concurrentDuplicateOnInsert() {
        when(disputeRepository.insert(any(Dispute.class))).thenThrow(new DuplicateKeyException("dup"));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.CONFLICT,
                "DISPUTE_ALREADY_EXISTS");
        verify(bookingFacade, never()).markDisputed(anyString(), any(), anyString());
    }

    // ---------- booking already DISPUTED (eligibility checked by the Booking module) ----------

    @Test
    @DisplayName("raise: legitimate already-DISPUTED booking -> PENDING insert, NO markDisputed, then SYNCED")
    void alreadyDisputedEligibleCreatesDispute() {
        eligible(true);

        DisputeDetailResponse result = raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        verify(eligibilityPort).assertEligible(BOOKING_ID);
        assertThat(insertedDispute().getBookingSyncState()).isEqualTo(BookingSyncState.PENDING);
        verify(bookingFacade, never()).markDisputed(anyString(), any(), anyString());
        verify(syncStore).markSynced(eq(DISPUTE_ID), anyString());
        assertThat(result.id()).isEqualTo(DISPUTE_ID);
    }

    static Stream<Arguments> eligibilityErrors() {
        return Stream.of(
                Arguments.of("expired window (incl. already DISPUTED > 72h)",
                        new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "closed")),
                Arguments.of("ineligible party", new AccessDeniedException("Access denied: not a participant")),
                Arguments.of("other actor id", new AccessDeniedException("Access denied: actor does not match")),
                Arguments.of("not logged in", new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "auth")),
                Arguments.of("unknown booking", new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found")),
                Arguments.of("not disputable status", new ResourceConflictException("INVALID_STATUS_TRANSITION", "no")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eligibilityErrors")
    @DisplayName("raise: not eligible -> the Booking module's error unchanged, nothing saved, no duplicate lookup")
    void ineligibleRejected(String name, RuntimeException facadeError) {
        when(eligibilityPort.assertEligible(BOOKING_ID)).thenThrow(facadeError);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(facadeError);
        verify(disputeRepository, never()).findByActiveBookingKey(anyString());
        assertNoBookingChangeAndNothingSaved();
    }

    // ---------- duplicates and retries ----------

    @Test
    @DisplayName("raise: a visible active dispute exists -> 409 DISPUTE_ALREADY_EXISTS, nothing changed")
    void visibleDuplicateRejected() {
        eligible(true);
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID))
                .thenReturn(Optional.of(dispute(PROVIDER_ID, BookingSyncState.SYNCED)));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.CONFLICT,
                "DISPUTE_ALREADY_EXISTS");
        assertNoBookingChangeAndNothingSaved();
    }

    @Test
    @DisplayName("raise: another user's PENDING intent holds the booking -> 409 DISPUTE_ALREADY_EXISTS")
    void otherUsersPendingIntentRejected() {
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID))
                .thenReturn(Optional.of(dispute(PROVIDER_ID, BookingSyncState.PENDING)));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.CONFLICT,
                "DISPUTE_ALREADY_EXISTS");
        assertNoBookingChangeAndNothingSaved();
    }

    @Test
    @DisplayName("raise: retry with own PENDING intent, booking already DISPUTED -> only marked SYNCED, no new record")
    void retryCompletesOwnIntent() {
        eligible(true);
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID))
                .thenReturn(Optional.of(dispute(CUSTOMER_ID, BookingSyncState.PENDING)));

        assertThat(raiseAs(CUSTOMER_ID, Role.CUSTOMER).id()).isEqualTo(DISPUTE_ID);

        InOrder order = inOrder(syncStore);
        order.verify(syncStore).claimById(DISPUTE_ID, NOW, Duration.ofMinutes(5));
        order.verify(syncStore).markSynced(eq(DISPUTE_ID), anyString());
        verify(disputeRepository, never()).insert(any(Dispute.class));
        verify(bookingFacade, never()).markDisputed(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("raise: retry with own PENDING intent, booking not DISPUTED -> markDisputed then SYNCED, no new record")
    void retryMovesBookingThenCompletes() {
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID))
                .thenReturn(Optional.of(dispute(CUSTOMER_ID, BookingSyncState.PENDING)));

        raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        InOrder order = inOrder(syncStore, bookingFacade);
        order.verify(syncStore).claimById(DISPUTE_ID, NOW, Duration.ofMinutes(5));
        order.verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        order.verify(syncStore).markSynced(eq(DISPUTE_ID), anyString());
        verify(disputeRepository, never()).insert(any(Dispute.class));
    }

    @Test
    @DisplayName("raise: retry while another request/the reconciler holds the record -> 503 DISPUTE_SYNC_PENDING, "
            + "booking NOT touched, record NOT removed")
    void retryWhileBusyChangesNothing() {
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID))
                .thenReturn(Optional.of(dispute(CUSTOMER_ID, BookingSyncState.PENDING)));
        when(syncStore.claimById(eq(DISPUTE_ID), eq(NOW), any(Duration.class))).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE,
                "DISPUTE_SYNC_PENDING");

        verify(bookingFacade, never()).markDisputed(anyString(), any(), anyString());
        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        verify(syncStore, never()).markSynced(anyString(), anyString());
        verify(disputeRepository, never()).insert(any(Dispute.class));
        verifyNoInteractions(payoutHoldPort);
    }

    @Test
    @DisplayName("raise: a retry that is rejected deletes only with ITS OWN lease token")
    void retryRejectedDeletesWithOwnToken() {
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID))
                .thenReturn(Optional.of(dispute(CUSTOMER_ID, BookingSyncState.PENDING)));
        ResourceConflictException closed = new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "closed");
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON)).thenThrow(closed);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(closed);

        verify(syncStore).deleteIfPending(DISPUTE_ID, RETRY_OWNER);
    }

    // ---------- the dispute module's own checks ----------

    @Test
    @DisplayName("raise: caller is not a party of the booking -> 403, booking untouched, no duplicate lookup")
    void nonPartyRejected() {
        loggedInAs("someone-else", Role.CUSTOMER);

        assertThat(catchThrowable(() -> service.raiseDispute("someone-else", Role.CUSTOMER, request())))
                .isInstanceOf(AccessDeniedException.class);
        verify(disputeRepository, never()).findByActiveBookingKey(anyString());
        assertNoBookingChangeAndNothingSaved();
    }

    @Test
    @DisplayName("raise: the actor passed to markDisputed is always CurrentUser, even if the service got another id")
    void actorIsAlwaysTheLoggedInUser() {
        loggedInAs(CUSTOMER_ID, Role.CUSTOMER);

        service.raiseDispute(PROVIDER_ID, Role.PROVIDER, request());

        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, TIMELINE_REASON);
        verify(bookingFacade, never()).markDisputed(BOOKING_ID, PROVIDER_ID, TIMELINE_REASON);
    }

    @Test
    @DisplayName("raise: nobody logged in -> no actor is passed, so the facade's own 401 applies; the intent is kept "
            + "for the reconciler (401 is not a confirmed rejection)")
    void noLoggedInUserPassesNullActor() {
        ApiException unauthorized = new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        when(bookingFacade.markDisputed(BOOKING_ID, null, TIMELINE_REASON)).thenThrow(unauthorized);

        assertThat(catchThrowable(() -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, request())))
                .isSameAs(unauthorized);
        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        String owner = insertedDispute().getSyncLeaseOwner();
        verify(syncStore).releaseLease(DISPUTE_ID, owner);
    }

    @Test
    @DisplayName("visibility: PENDING is invisible; SYNCED and legacy records without the field are visible")
    void intentIsInvisible() {
        Dispute intent = dispute(CUSTOMER_ID, BookingSyncState.PENDING);
        assertThat(intent.isVisible()).isFalse();
        assertThat(dispute(CUSTOMER_ID, BookingSyncState.SYNCED).isVisible()).isTrue();
        ReflectionTestUtils.setField(intent, "bookingSyncState", null);
        assertThat(intent.isVisible()).isTrue();
    }
}
