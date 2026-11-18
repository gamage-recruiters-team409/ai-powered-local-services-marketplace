package com.gamagerecruiters.marketplace.modules.completion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ReportNoShowRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;

/**
 * Unit tests for the completion rules. MongoDB transactions and conditional updates are covered
 * against a real MongoDB in CompletionConsistencyIntegrationTest; here the transaction runner only
 * records that every write runs inside one transaction.
 */
@ExtendWith(MockitoExtension.class)
class ServiceCompletionServiceImplTest {

    private static final String BOOKING = "booking-1";
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant SLOT_START = NOW.minusSeconds(3 * 3600);
    private static final Instant SLOT_END = NOW.minusSeconds(3600);
    private static final Instant WINDOW_START = NOW.minus(Duration.ofHours(48));

    @Mock
    private ServiceCompletionRepository repository;

    @Mock
    private BookingFacade bookingFacade;

    @Mock
    private CompletionAtomicUpdates atomicUpdates;

    private RecordingTransactions transactions;
    private ServiceCompletionServiceImpl service;

    /** Runs the work directly and counts how often a transaction was opened. */
    private static final class RecordingTransactions implements CompletionTransactions {
        private int opened;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            opened++;
            return work.get();
        }
    }

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactions();
        service = new ServiceCompletionServiceImpl(repository, bookingFacade, atomicUpdates, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BookingParticipants booking(BookingStatus status) {
        return booking(status, SLOT_START);
    }

    private static BookingParticipants booking(BookingStatus status, Instant slotStart) {
        return new BookingParticipants(BOOKING, null, "listing-1", CUSTOMER, PROVIDER, status, slotStart, SLOT_END);
    }

    private static ServiceCompletion started() {
        ServiceCompletion completion = new ServiceCompletion();
        completion.setId("comp-1");
        completion.setBookingId(BOOKING);
        completion.setCustomerId(CUSTOMER);
        completion.setProviderId(PROVIDER);
        completion.setStartedAt(NOW.minusSeconds(3600));
        completion.setCreatedAt(NOW.minusSeconds(3600));
        completion.setUpdatedAt(NOW.minusSeconds(3600));
        return completion;
    }

    private static ServiceCompletion markedCompleted() {
        ServiceCompletion completion = started();
        completion.setCompletionNote("Done");
        completion.setMarkedCompletedAt(NOW.minusSeconds(600));
        return completion;
    }

    private static ServiceCompletion confirmed(CompletionConfirmationType type) {
        ServiceCompletion completion = markedCompleted();
        completion.setConfirmedAt(NOW);
        completion.setConfirmationType(type);
        return completion;
    }

    private static ServiceCompletion issueRaised() {
        ServiceCompletion completion = markedCompleted();
        completion.setIssueRaisedAt(NOW);
        return completion;
    }

    private static void assertApiError(ApiException ex, HttpStatus status, String code) {
        assertEquals(status, ex.getStatus());
        assertEquals(code, ex.getErrorCode());
    }

    // ------------------------------------------------------------------ start

    @Nested
    @DisplayName("startService")
    class StartService {

        @Test
        @DisplayName("Provider starts a CONFIRMED booking: record inserted, then booking moved to IN_PROGRESS, in one transaction")
        void startsConfirmedBooking() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(repository.insert(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            ServiceCompletionResponse response = service.startService(BOOKING, PROVIDER);

            assertEquals(BOOKING, response.bookingId());
            assertEquals(NOW, response.startedAt());
            assertEquals(1, transactions.opened);
            InOrder order = inOrder(repository, bookingFacade);
            order.verify(repository).insert(any(ServiceCompletion.class));
            order.verify(bookingFacade).markInProgress(BOOKING, PROVIDER);
        }

        @Test
        @DisplayName("Another provider gets 403 ACCESS_DENIED and nothing is written")
        void rejectsOtherProvider() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));

            assertThrows(AccessDeniedException.class, () -> service.startService(BOOKING, "prov-2"));

            verify(repository, never()).insert(any(ServiceCompletion.class));
            verify(bookingFacade, never()).markInProgress(anyString(), anyString());
        }

        @Test
        @DisplayName("Booking not CONFIRMED gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Already started gets 409 SERVICE_ALREADY_STARTED")
        void rejectsAlreadyStarted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(true);

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_STARTED");
            verify(bookingFacade, never()).markInProgress(anyString(), anyString());
        }

        @Test
        @DisplayName("Concurrent start (duplicate bookingId) gets 409 SERVICE_ALREADY_STARTED")
        void concurrentStartHitsUniqueIndex() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(repository.insert(any(ServiceCompletion.class))).thenThrow(new DuplicateKeyException("dup"));

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_STARTED");
            verify(bookingFacade, never()).markInProgress(anyString(), anyString());
        }

        @Test
        @DisplayName("Booking rejects the transition: the error ends the transaction (no manual delete)")
        void bookingErrorEndsTheTransaction() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(repository.insert(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            ResourceConflictException bookingError = new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "changed");
            when(bookingFacade.markInProgress(BOOKING, PROVIDER)).thenThrow(bookingError);

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertSame(bookingError, ex);
            verify(repository, never()).delete(any(ServiceCompletion.class));
        }
    }

    // ------------------------------------------------------------------ mark completed

    @Nested
    @DisplayName("markCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("Provider marks an IN_PROGRESS service completed: conditional update with trimmed note and photos, then booking")
        void marksCompleted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));
            ServiceCompletion updated = markedCompleted();
            when(atomicUpdates.markCompleted(BOOKING, "Cleaned 3 rooms", List.of("https://img/1.jpg"), NOW))
                    .thenReturn(Optional.of(updated));

            ServiceCompletionResponse response = service.markCompleted(BOOKING, PROVIDER,
                    new MarkCompletedRequest("  Cleaned 3 rooms  ", List.of(" https://img/1.jpg ", "  ")));

            assertEquals(updated.getMarkedCompletedAt(), response.markedCompletedAt());
            assertEquals(1, transactions.opened);
            InOrder order = inOrder(atomicUpdates, bookingFacade);
            order.verify(atomicUpdates).markCompleted(eq(BOOKING), any(), any(), eq(NOW));
            order.verify(bookingFacade).markCompletionPending(BOOKING, PROVIDER);
            verify(repository, never()).save(any(ServiceCompletion.class));
        }

        @Test
        @DisplayName("Empty request body is accepted (no note, no photos)")
        void acceptsEmptyRequest() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));
            when(atomicUpdates.markCompleted(BOOKING, null, List.of(), NOW)).thenReturn(Optional.of(markedCompleted()));

            service.markCompleted(BOOKING, PROVIDER, null);

            verify(atomicUpdates).markCompleted(BOOKING, null, List.of(), NOW);
        }

        @Test
        @DisplayName("Booking not IN_PROGRESS gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));

            ApiException ex = assertThrows(ApiException.class, () -> service.markCompleted(BOOKING, PROVIDER, null));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Service never started gets 404 COMPLETION_NOT_FOUND")
        void rejectsNotStarted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class, () -> service.markCompleted(BOOKING, PROVIDER, null));

            assertApiError(ex, HttpStatus.NOT_FOUND, "COMPLETION_NOT_FOUND");
        }

        @Test
        @DisplayName("Already marked completed gets 409 SERVICE_ALREADY_MARKED_COMPLETED")
        void rejectsAlreadyMarked() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));

            ApiException ex = assertThrows(ApiException.class, () -> service.markCompleted(BOOKING, PROVIDER, null));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_MARKED_COMPLETED");
            verify(atomicUpdates, never()).markCompleted(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("Marked by a concurrent request (conditional update matches nothing): 409, booking not touched")
        void concurrentMarkIsRejected() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));
            when(atomicUpdates.markCompleted(eq(BOOKING), any(), any(), eq(NOW))).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class, () -> service.markCompleted(BOOKING, PROVIDER, null));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_MARKED_COMPLETED");
            verify(bookingFacade, never()).markCompletionPending(anyString(), anyString());
        }
    }

    // ------------------------------------------------------------------ confirm

    @Nested
    @DisplayName("confirmCompletion")
    class ConfirmCompletion {

        @Test
        @DisplayName("Customer confirms a COMPLETION_PENDING service: conditional update CUSTOMER, then booking COMPLETED")
        void confirms() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.CUSTOMER, NOW, null))
                    .thenReturn(Optional.of(confirmed(CompletionConfirmationType.CUSTOMER)));

            ServiceCompletionResponse response = service.confirmCompletion(BOOKING, CUSTOMER);

            assertEquals(NOW, response.confirmedAt());
            assertEquals(CompletionConfirmationType.CUSTOMER, response.confirmationType());
            assertEquals(1, transactions.opened);
            InOrder order = inOrder(atomicUpdates, bookingFacade);
            order.verify(atomicUpdates).confirm(BOOKING, CompletionConfirmationType.CUSTOMER, NOW, null);
            order.verify(bookingFacade).markCompleted(BOOKING, CUSTOMER);
        }

        @Test
        @DisplayName("Another customer gets 403 ACCESS_DENIED")
        void rejectsOtherCustomer() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));

            assertThrows(AccessDeniedException.class, () -> service.confirmCompletion(BOOKING, "cust-2"));

            verify(atomicUpdates, never()).confirm(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("Booking not COMPLETION_PENDING gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Not marked completed yet gets 409 SERVICE_NOT_MARKED_COMPLETED")
        void rejectsNotMarked() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_NOT_MARKED_COMPLETED");
        }

        @Test
        @DisplayName("Issue already raised gets 409 COMPLETION_ISSUE_RAISED")
        void rejectsWhenIssueRaised() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(issueRaised()));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ISSUE_RAISED");
            verify(atomicUpdates, never()).confirm(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("Already confirmed gets 409 COMPLETION_ALREADY_CONFIRMED")
        void rejectsAlreadyConfirmed() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(confirmed(CompletionConfirmationType.AUTO)));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ALREADY_CONFIRMED");
        }

        @Test
        @DisplayName("Issue raised by a concurrent request: update matches nothing, 409 COMPLETION_ISSUE_RAISED, booking not touched")
        void concurrentIssueWins() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING))
                    .thenReturn(Optional.of(markedCompleted()), Optional.of(issueRaised()));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.CUSTOMER, NOW, null)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ISSUE_RAISED");
            verify(bookingFacade, never()).markCompleted(anyString(), anyString());
            verify(repository, never()).save(any(ServiceCompletion.class));
        }

        @Test
        @DisplayName("Auto-confirmed at the same moment: 409 COMPLETION_ALREADY_CONFIRMED, the AUTO result is kept")
        void concurrentAutoConfirmWins() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING))
                    .thenReturn(Optional.of(markedCompleted()), Optional.of(confirmed(CompletionConfirmationType.AUTO)));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.CUSTOMER, NOW, null)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ALREADY_CONFIRMED");
            verify(bookingFacade, never()).markCompleted(anyString(), anyString());
        }

        @Test
        @DisplayName("Booking rejects the transition: the error ends the transaction (no manual revert)")
        void bookingErrorEndsTheTransaction() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.CUSTOMER, NOW, null))
                    .thenReturn(Optional.of(confirmed(CompletionConfirmationType.CUSTOMER)));
            when(bookingFacade.markCompleted(BOOKING, CUSTOMER))
                    .thenThrow(new ResourceConflictException("INVALID_STATUS_TRANSITION", "booking moved"));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
            verify(repository, never()).save(any(ServiceCompletion.class));
        }
    }

    // ------------------------------------------------------------------ raise issue

    @Nested
    @DisplayName("raiseIssue")
    class RaiseIssue {

        @Test
        @DisplayName("Customer raises an issue within 48h: conditional update inside the window, booking disputed with the reason")
        void raisesIssue() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));
            when(atomicUpdates.raiseIssue(BOOKING, NOW, WINDOW_START)).thenReturn(Optional.of(issueRaised()));

            ServiceCompletionResponse response = service.raiseIssue(BOOKING, CUSTOMER,
                    new RaiseIssueRequest("  The garden was not cleaned  "));

            assertEquals(NOW, response.issueRaisedAt());
            assertEquals(1, transactions.opened);
            InOrder order = inOrder(atomicUpdates, bookingFacade);
            order.verify(atomicUpdates).raiseIssue(BOOKING, NOW, WINDOW_START);
            order.verify(bookingFacade).markDisputed(BOOKING, CUSTOMER, "CUSTOMER_ISSUE: The garden was not cleaned");
        }

        @Test
        @DisplayName("After the 48h window gets 409 CONFIRMATION_WINDOW_CLOSED")
        void rejectsAfterWindow() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(WINDOW_START.minusSeconds(1));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("The garden was not cleaned")));

            assertApiError(ex, HttpStatus.CONFLICT, "CONFIRMATION_WINDOW_CLOSED");
            verify(atomicUpdates, never()).raiseIssue(anyString(), any(), any());
        }

        @Test
        @DisplayName("Provider cannot raise an issue (403 ACCESS_DENIED)")
        void providerCannotRaise() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));

            assertThrows(AccessDeniedException.class,
                    () -> service.raiseIssue(BOOKING, PROVIDER, new RaiseIssueRequest("The garden was not cleaned")));
        }

        @Test
        @DisplayName("Already confirmed gets 409 COMPLETION_ALREADY_CONFIRMED")
        void rejectsAlreadyConfirmed() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(confirmed(CompletionConfirmationType.CUSTOMER)));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("The garden was not cleaned")));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ALREADY_CONFIRMED");
        }

        @Test
        @DisplayName("Confirmed by a concurrent request: update matches nothing, 409 COMPLETION_ALREADY_CONFIRMED, no dispute")
        void concurrentConfirmationWins() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING))
                    .thenReturn(Optional.of(markedCompleted()), Optional.of(confirmed(CompletionConfirmationType.CUSTOMER)));
            when(atomicUpdates.raiseIssue(BOOKING, NOW, WINDOW_START)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("The garden was not cleaned")));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ALREADY_CONFIRMED");
            verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Update matches nothing but the record shows no decision: 409 COMPLETION_UPDATE_CONFLICT")
        void unexplainedRaceIsAConflict() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));
            when(atomicUpdates.raiseIssue(BOOKING, NOW, WINDOW_START)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("The garden was not cleaned")));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_UPDATE_CONFLICT");
        }
    }

    // ------------------------------------------------------------------ no-show

    @Nested
    @DisplayName("reportNoShow")
    class ReportNoShow {

        @Test
        @DisplayName("Provider reports customer no-show 120+ min after start: NO_SHOW_BY_CUSTOMER, booking disputed")
        void providerReportsCustomerNoShow() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(bookingFacade.markDisputed(BOOKING, PROVIDER, "NO_SHOW_BY_CUSTOMER: Nobody home"))
                    .thenReturn(booking(BookingStatus.DISPUTED));

            NoShowReportResponse response = service.reportNoShow(BOOKING, PROVIDER, new ReportNoShowRequest(" Nobody home "));

            assertEquals("NO_SHOW_BY_CUSTOMER", response.type());
            assertEquals("DISPUTED", response.bookingStatus());
            assertEquals(NOW, response.reportedAt());
            assertEquals(1, transactions.opened);
        }

        @Test
        @DisplayName("Customer reports provider no-show without a note: NO_SHOW_BY_PROVIDER")
        void customerReportsProviderNoShow() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(bookingFacade.markDisputed(BOOKING, CUSTOMER, "NO_SHOW_BY_PROVIDER"))
                    .thenReturn(booking(BookingStatus.DISPUTED));

            NoShowReportResponse response = service.reportNoShow(BOOKING, CUSTOMER, null);

            assertEquals("NO_SHOW_BY_PROVIDER", response.type());
        }

        @Test
        @DisplayName("Less than 120 min after start gets 409 NO_SHOW_TOO_EARLY")
        void rejectsTooEarly() {
            when(bookingFacade.getBookingParticipants(BOOKING))
                    .thenReturn(booking(BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(119))));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);

            ApiException ex = assertThrows(ApiException.class, () -> service.reportNoShow(BOOKING, CUSTOMER, null));

            assertApiError(ex, HttpStatus.CONFLICT, "NO_SHOW_TOO_EARLY");
            verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Service already started gets 409 SERVICE_ALREADY_STARTED")
        void rejectsWhenStarted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(true);

            ApiException ex = assertThrows(ApiException.class, () -> service.reportNoShow(BOOKING, CUSTOMER, null));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_STARTED");
        }

        @Test
        @DisplayName("Booking not CONFIRMED gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));

            ApiException ex = assertThrows(ApiException.class, () -> service.reportNoShow(BOOKING, CUSTOMER, null));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Someone outside the booking gets 403 ACCESS_DENIED")
        void strangerIsRejected() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));

            assertThrows(AccessDeniedException.class, () -> service.reportNoShow(BOOKING, "someone-else", null));
        }
    }

    // ------------------------------------------------------------------ auto-confirm

    @Nested
    @DisplayName("autoConfirm")
    class AutoConfirm {

        private ServiceCompletion due() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(NOW.minus(Duration.ofHours(49)));
            return completion;
        }

        @Test
        @DisplayName("Candidates are completions marked more than 48h ago with no confirmation or issue")
        void findsCandidates() {
            when(repository.findByMarkedCompletedAtBeforeAndConfirmedAtIsNullAndIssueRaisedAtIsNull(WINDOW_START))
                    .thenReturn(List.of(due()));

            assertEquals(List.of(BOOKING), service.findAutoConfirmCandidates(NOW));
        }

        @Test
        @DisplayName("Due completion: conditional AUTO update (only if still due), then markCompletedBySystem, in one transaction")
        void confirmsAutomatically() {
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(due()));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.AUTO, NOW, WINDOW_START))
                    .thenReturn(Optional.of(confirmed(CompletionConfirmationType.AUTO)));

            assertTrue(service.autoConfirm(BOOKING));

            assertEquals(1, transactions.opened);
            InOrder order = inOrder(atomicUpdates, bookingFacade);
            order.verify(atomicUpdates).confirm(BOOKING, CompletionConfirmationType.AUTO, NOW, WINDOW_START);
            order.verify(bookingFacade).markCompletedBySystem(BOOKING);
            verify(bookingFacade, never()).markCompleted(anyString(), anyString());
        }

        @Test
        @DisplayName("Skips when an issue was raised or the window has not ended")
        void skipsWhenNotDue() {
            ServiceCompletion withIssue = due();
            withIssue.setIssueRaisedAt(NOW.minus(Duration.ofHours(1)));
            ServiceCompletion tooRecent = markedCompleted();
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(withIssue), Optional.of(tooRecent));

            assertFalse(service.autoConfirm(BOOKING));
            assertFalse(service.autoConfirm(BOOKING));

            verify(atomicUpdates, never()).confirm(anyString(), any(), any(), any());
            verify(bookingFacade, never()).markCompletedBySystem(anyString());
        }

        @Test
        @DisplayName("Customer decided at the same moment (update matches nothing): skipped, booking not touched")
        void customerDecisionWins() {
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(due()));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.AUTO, NOW, WINDOW_START))
                    .thenReturn(Optional.empty());

            assertFalse(service.autoConfirm(BOOKING));

            verify(bookingFacade, never()).markCompletedBySystem(anyString());
        }

        @Test
        @DisplayName("Booking says not due yet (409 AUTO_COMPLETION_NOT_DUE): transaction rolled back, retried on the next run")
        void notDueInBookingIsRetriedLater() {
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(due()));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.AUTO, NOW, WINDOW_START))
                    .thenReturn(Optional.of(confirmed(CompletionConfirmationType.AUTO)));
            when(bookingFacade.markCompletedBySystem(BOOKING))
                    .thenThrow(new ResourceConflictException("AUTO_COMPLETION_NOT_DUE", "Not due yet"));

            assertFalse(service.autoConfirm(BOOKING));
        }

        @Test
        @DisplayName("Other booking errors end the transaction and are rethrown")
        void otherErrorsAreRethrown() {
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(due()));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(atomicUpdates.confirm(BOOKING, CompletionConfirmationType.AUTO, NOW, WINDOW_START))
                    .thenReturn(Optional.of(confirmed(CompletionConfirmationType.AUTO)));
            when(bookingFacade.markCompletedBySystem(BOOKING))
                    .thenThrow(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "Changed at the same time"));

            ApiException ex = assertThrows(ApiException.class, () -> service.autoConfirm(BOOKING));

            assertEquals("BOOKING_UPDATE_CONFLICT", ex.getErrorCode());
            verify(repository, never()).save(any(ServiceCompletion.class));
        }

        @Test
        @DisplayName("Skips when the booking is no longer COMPLETION_PENDING")
        void skipsWhenStatusChanged() {
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(due()));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.DISPUTED));

            assertFalse(service.autoConfirm(BOOKING));

            verify(atomicUpdates, never()).confirm(anyString(), any(), any(), any());
        }
    }

    // ------------------------------------------------------------------ read

    @Nested
    @DisplayName("getCompletion")
    class GetCompletion {

        @Test
        @DisplayName("Booking customer and provider can both read the completion")
        void participantsCanRead() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));

            assertEquals(BOOKING, service.getCompletion(BOOKING, CUSTOMER).bookingId());
            assertEquals(BOOKING, service.getCompletion(BOOKING, PROVIDER).bookingId());
        }

        @Test
        @DisplayName("Someone outside the booking gets 403 ACCESS_DENIED")
        void strangerIsRejected() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));

            assertThrows(AccessDeniedException.class, () -> service.getCompletion(BOOKING, "someone-else"));
        }

        @Test
        @DisplayName("No completion yet gets 404 COMPLETION_NOT_FOUND")
        void notStartedYet() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class, () -> service.getCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.NOT_FOUND, "COMPLETION_NOT_FOUND");
        }
    }
}
