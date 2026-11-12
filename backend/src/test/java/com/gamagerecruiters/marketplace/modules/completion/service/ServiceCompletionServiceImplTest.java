package com.gamagerecruiters.marketplace.modules.completion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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

@ExtendWith(MockitoExtension.class)
class ServiceCompletionServiceImplTest {

    private static final String BOOKING = "booking-1";
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant SLOT_START = NOW.minusSeconds(3 * 3600);
    private static final Instant SLOT_END = NOW.minusSeconds(3600);

    @Mock
    private ServiceCompletionRepository repository;

    @Mock
    private BookingFacade bookingFacade;

    private ServiceCompletionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ServiceCompletionServiceImpl(repository, bookingFacade, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The only place the record is built: after the booking follow-up PR adds slotStart/slotEnd, add them here. */
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

    private static void assertApiError(ApiException ex, HttpStatus status, String code) {
        assertEquals(status, ex.getStatus());
        assertEquals(code, ex.getErrorCode());
    }

    // ------------------------------------------------------------------ start

    @Nested
    @DisplayName("startService")
    class StartService {

        @Test
        @DisplayName("Provider starts a CONFIRMED booking: record saved first, then booking moved to IN_PROGRESS")
        void startsConfirmedBooking() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            ServiceCompletionResponse response = service.startService(BOOKING, PROVIDER);

            assertEquals(BOOKING, response.bookingId());
            assertEquals(NOW, response.startedAt());
            InOrder order = inOrder(repository, bookingFacade);
            order.verify(repository).save(any(ServiceCompletion.class));
            order.verify(bookingFacade).markInProgress(BOOKING, PROVIDER);
        }

        @Test
        @DisplayName("Another provider gets 403 ACCESS_DENIED and nothing is saved")
        void rejectsOtherProvider() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));

            assertThrows(AccessDeniedException.class, () -> service.startService(BOOKING, "prov-2"));

            verify(repository, never()).save(any());
            verify(bookingFacade, never()).markInProgress(anyString(), anyString());
        }

        @Test
        @DisplayName("Booking not CONFIRMED gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.AWAITING_PAYMENT));

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
            verify(repository, never()).save(any());
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
            when(repository.save(any(ServiceCompletion.class))).thenThrow(new DuplicateKeyException("dup"));

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_STARTED");
            verify(bookingFacade, never()).markInProgress(anyString(), anyString());
        }

        @Test
        @DisplayName("If booking rejects the transition, the new completion record is removed")
        void removesRecordWhenBookingRejects() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            ApiException bookingError = new ApiException(HttpStatus.CONFLICT, "BOOKING_UPDATE_CONFLICT", "conflict");
            when(bookingFacade.markInProgress(BOOKING, PROVIDER)).thenThrow(bookingError);

            ApiException ex = assertThrows(ApiException.class, () -> service.startService(BOOKING, PROVIDER));

            assertSame(bookingError, ex);
            verify(repository).delete(any(ServiceCompletion.class));
        }
    }

    // ------------------------------------------------------------------ mark completed

    @Nested
    @DisplayName("markCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("Provider marks an IN_PROGRESS service completed with trimmed note and photos")
        void marksCompleted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            ServiceCompletionResponse response = service.markCompleted(BOOKING, PROVIDER,
                    new MarkCompletedRequest("  Fixed the leak  ", List.of(" https://img/1.jpg ", "  ")));

            assertEquals("Fixed the leak", response.completionNote());
            assertEquals(List.of("https://img/1.jpg"), response.photoUrls());
            assertEquals(NOW, response.markedCompletedAt());
            InOrder order = inOrder(repository, bookingFacade);
            order.verify(repository).save(any(ServiceCompletion.class));
            order.verify(bookingFacade).markCompletionPending(BOOKING, PROVIDER);
        }

        @Test
        @DisplayName("Empty request body is accepted (no note, no photos)")
        void acceptsEmptyRequest() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            ServiceCompletionResponse response = service.markCompleted(BOOKING, PROVIDER, new MarkCompletedRequest(null, null));

            assertNull(response.completionNote());
            assertTrue(response.photoUrls().isEmpty());
        }

        @Test
        @DisplayName("Booking not IN_PROGRESS gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.markCompleted(BOOKING, PROVIDER, new MarkCompletedRequest(null, null)));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Service never started gets 404 COMPLETION_NOT_FOUND")
        void rejectsNotStarted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.markCompleted(BOOKING, PROVIDER, new MarkCompletedRequest(null, null)));

            assertApiError(ex, HttpStatus.NOT_FOUND, "COMPLETION_NOT_FOUND");
        }

        @Test
        @DisplayName("Already marked completed gets 409 SERVICE_ALREADY_MARKED_COMPLETED")
        void rejectsAlreadyMarked() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.markCompleted(BOOKING, PROVIDER, new MarkCompletedRequest(null, null)));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_MARKED_COMPLETED");
        }

        @Test
        @DisplayName("If booking rejects the transition, the completion evidence is reverted")
        void revertsWhenBookingRejects() {
            ServiceCompletion completion = started();
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bookingFacade.markCompletionPending(BOOKING, PROVIDER))
                    .thenThrow(new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "no"));

            assertThrows(ApiException.class,
                    () -> service.markCompleted(BOOKING, PROVIDER, new MarkCompletedRequest("Note", List.of("u"))));

            assertNull(completion.getMarkedCompletedAt());
            assertNull(completion.getCompletionNote());
        }
    }

    // ------------------------------------------------------------------ confirm

    @Nested
    @DisplayName("confirmCompletion")
    class ConfirmCompletion {

        @Test
        @DisplayName("Customer confirms a COMPLETION_PENDING service: confirmationType CUSTOMER, booking COMPLETED")
        void confirms() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            ServiceCompletionResponse response = service.confirmCompletion(BOOKING, CUSTOMER);

            assertEquals(NOW, response.confirmedAt());
            assertEquals(CompletionConfirmationType.CUSTOMER, response.confirmationType());
            verify(bookingFacade).markCompleted(BOOKING, CUSTOMER);
        }

        @Test
        @DisplayName("Another customer gets 403 ACCESS_DENIED")
        void rejectsOtherCustomer() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));

            assertThrows(AccessDeniedException.class, () -> service.confirmCompletion(BOOKING, "cust-2"));

            verify(bookingFacade, never()).markCompleted(anyString(), anyString());
        }

        @Test
        @DisplayName("Booking not COMPLETION_PENDING gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Issue already raised gets 409 COMPLETION_ISSUE_RAISED")
        void rejectsWhenIssueRaised() {
            ServiceCompletion completion = markedCompleted();
            completion.setIssueRaisedAt(NOW.minusSeconds(60));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ISSUE_RAISED");
        }

        @Test
        @DisplayName("Already confirmed gets 409 COMPLETION_ALREADY_CONFIRMED")
        void rejectsAlreadyConfirmed() {
            ServiceCompletion completion = markedCompleted();
            completion.setConfirmedAt(NOW.minusSeconds(60));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));

            ApiException ex = assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ALREADY_CONFIRMED");
        }

        @Test
        @DisplayName("If booking rejects the transition, the confirmation is reverted")
        void revertsWhenBookingRejects() {
            ServiceCompletion completion = markedCompleted();
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bookingFacade.markCompleted(BOOKING, CUSTOMER))
                    .thenThrow(new ApiException(HttpStatus.CONFLICT, "BOOKING_UPDATE_CONFLICT", "conflict"));

            assertThrows(ApiException.class, () -> service.confirmCompletion(BOOKING, CUSTOMER));

            assertNull(completion.getConfirmedAt());
            assertNull(completion.getConfirmationType());
        }
    }


    // ------------------------------------------------------------------ raise issue

    @Nested
    @DisplayName("raiseIssue")
    class RaiseIssue {

        @Test
        @DisplayName("Customer raises an issue within 48h: issueRaisedAt set, booking disputed with the reason")
        void raisesIssue() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(markedCompleted()));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            ServiceCompletionResponse response =
                    service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("  Balcony was not cleaned.  "));

            assertEquals(NOW, response.issueRaisedAt());
            verify(bookingFacade).markDisputed(BOOKING, CUSTOMER, "CUSTOMER_ISSUE: Balcony was not cleaned.");
        }

        @Test
        @DisplayName("After the 48h window gets 409 CONFIRMATION_WINDOW_CLOSED")
        void rejectsAfterWindow() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(NOW.minus(Duration.ofHours(48)).minusSeconds(1));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("Balcony was not cleaned.")));

            assertApiError(ex, HttpStatus.CONFLICT, "CONFIRMATION_WINDOW_CLOSED");
            verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Provider cannot raise an issue (403 ACCESS_DENIED)")
        void providerCannotRaise() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));

            assertThrows(AccessDeniedException.class,
                    () -> service.raiseIssue(BOOKING, PROVIDER, new RaiseIssueRequest("Balcony was not cleaned.")));
        }

        @Test
        @DisplayName("Already confirmed gets 409 COMPLETION_ALREADY_CONFIRMED")
        void rejectsAlreadyConfirmed() {
            ServiceCompletion completion = markedCompleted();
            completion.setConfirmedAt(NOW.minusSeconds(60));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("Balcony was not cleaned.")));

            assertApiError(ex, HttpStatus.CONFLICT, "COMPLETION_ALREADY_CONFIRMED");
        }

        @Test
        @DisplayName("If booking rejects the dispute, issueRaisedAt is reverted")
        void revertsWhenBookingRejects() {
            ServiceCompletion completion = markedCompleted();
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bookingFacade.markDisputed(anyString(), anyString(), anyString()))
                    .thenThrow(new ApiException(HttpStatus.CONFLICT, "BOOKING_UPDATE_CONFLICT", "conflict"));

            assertThrows(ApiException.class,
                    () -> service.raiseIssue(BOOKING, CUSTOMER, new RaiseIssueRequest("Balcony was not cleaned.")));

            assertNull(completion.getIssueRaisedAt());
        }
    }

    // ------------------------------------------------------------------ no-show

    @Nested
    @DisplayName("reportNoShow")
    class ReportNoShow {

        @Test
        @DisplayName("Provider reports customer no-show 120+ min after start: NO_SHOW_BY_CUSTOMER, booking disputed")
        void providerReportsCustomerNoShow() {
            when(bookingFacade.getBookingParticipants(BOOKING))
                    .thenReturn(booking(BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(120))));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(bookingFacade.markDisputed(anyString(), anyString(), anyString())).thenReturn(booking(BookingStatus.DISPUTED));

            NoShowReportResponse response = service.reportNoShow(BOOKING, PROVIDER, new ReportNoShowRequest(" Waited 2 hours "));

            assertEquals("NO_SHOW_BY_CUSTOMER", response.type());
            assertEquals("DISPUTED", response.bookingStatus());
            verify(bookingFacade).markDisputed(BOOKING, PROVIDER, "NO_SHOW_BY_CUSTOMER: Waited 2 hours");
        }

        @Test
        @DisplayName("Customer reports provider no-show without a note: NO_SHOW_BY_PROVIDER")
        void customerReportsProviderNoShow() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);
            when(bookingFacade.markDisputed(anyString(), anyString(), anyString())).thenReturn(booking(BookingStatus.DISPUTED));

            NoShowReportResponse response = service.reportNoShow(BOOKING, CUSTOMER, null);

            assertEquals("NO_SHOW_BY_PROVIDER", response.type());
            verify(bookingFacade).markDisputed(BOOKING, CUSTOMER, "NO_SHOW_BY_PROVIDER");
        }

        @Test
        @DisplayName("Less than 120 min after start gets 409 NO_SHOW_TOO_EARLY")
        void rejectsTooEarly() {
            when(bookingFacade.getBookingParticipants(BOOKING))
                    .thenReturn(booking(BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(119))));
            when(repository.existsByBookingId(BOOKING)).thenReturn(false);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.reportNoShow(BOOKING, PROVIDER, new ReportNoShowRequest(null)));

            assertApiError(ex, HttpStatus.CONFLICT, "NO_SHOW_TOO_EARLY");
            verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Service already started gets 409 SERVICE_ALREADY_STARTED")
        void rejectsWhenStarted() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));
            when(repository.existsByBookingId(BOOKING)).thenReturn(true);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.reportNoShow(BOOKING, PROVIDER, new ReportNoShowRequest(null)));

            assertApiError(ex, HttpStatus.CONFLICT, "SERVICE_ALREADY_STARTED");
        }

        @Test
        @DisplayName("Booking not CONFIRMED gets 409 INVALID_STATUS_TRANSITION")
        void rejectsWrongStatus() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.reportNoShow(BOOKING, PROVIDER, new ReportNoShowRequest(null)));

            assertApiError(ex, HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("Someone outside the booking gets 403 ACCESS_DENIED")
        void strangerIsRejected() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED));

            assertThrows(AccessDeniedException.class,
                    () -> service.reportNoShow(BOOKING, "someone-else", new ReportNoShowRequest(null)));
        }
    }

    // ------------------------------------------------------------------ auto-confirm

    @Nested
    @DisplayName("autoConfirm")
    class AutoConfirm {

        @Test
        @DisplayName("Candidates are completions marked more than 48h ago with no confirmation or issue")
        void findsCandidates() {
            ServiceCompletion completion = markedCompleted();
            when(repository.findByMarkedCompletedAtBeforeAndConfirmedAtIsNullAndIssueRaisedAtIsNull(
                    NOW.minus(Duration.ofHours(48)))).thenReturn(List.of(completion));

            assertEquals(List.of(BOOKING), service.findAutoConfirmCandidates(NOW));
        }

        @Test
        @DisplayName("Due completion is confirmed as AUTO and booking completed through markCompletedBySystem")
        void confirmsAutomatically() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(NOW.minus(Duration.ofHours(49)));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));

            assertTrue(service.autoConfirm(BOOKING));

            assertEquals(CompletionConfirmationType.AUTO, completion.getConfirmationType());
            assertEquals(NOW, completion.getConfirmedAt());
            verify(bookingFacade).markCompletedBySystem(BOOKING);
            verify(bookingFacade, never()).markCompleted(anyString(), anyString());
        }

        @Test
        @DisplayName("Skips when an issue was raised or the window has not ended")
        void skipsWhenNotDue() {
            ServiceCompletion withIssue = markedCompleted();
            withIssue.setMarkedCompletedAt(NOW.minus(Duration.ofHours(49)));
            withIssue.setIssueRaisedAt(NOW.minus(Duration.ofHours(10)));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(withIssue));

            assertFalse(service.autoConfirm(BOOKING));

            ServiceCompletion notYet = markedCompleted();
            notYet.setMarkedCompletedAt(NOW.minus(Duration.ofHours(47)));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(notYet));

            assertFalse(service.autoConfirm(BOOKING));
            verify(bookingFacade, never()).markCompletedBySystem(anyString());
        }

        @Test
        @DisplayName("Booking says not due yet (409 AUTO_COMPLETION_NOT_DUE): completion reverted, retried on the next run")
        void notDueInBookingIsRetriedLater() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(NOW.minus(Duration.ofHours(48)));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bookingFacade.markCompletedBySystem(BOOKING))
                    .thenThrow(new ResourceConflictException("AUTO_COMPLETION_NOT_DUE", "Not due yet"));

            assertFalse(service.autoConfirm(BOOKING));

            assertNull(completion.getConfirmedAt());
            assertNull(completion.getConfirmationType());
        }

        @Test
        @DisplayName("Other booking errors revert the completion and are rethrown")
        void otherErrorsRevertAndRethrow() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(NOW.minus(Duration.ofHours(49)));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.COMPLETION_PENDING));
            when(repository.save(any(ServiceCompletion.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bookingFacade.markCompletedBySystem(BOOKING))
                    .thenThrow(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "Changed at the same time"));

            ApiException ex = assertThrows(ApiException.class, () -> service.autoConfirm(BOOKING));

            assertEquals("BOOKING_UPDATE_CONFLICT", ex.getErrorCode());
            assertNull(completion.getConfirmedAt());
            assertNull(completion.getConfirmationType());
        }

        @Test
        @DisplayName("Skips when the booking is no longer COMPLETION_PENDING")
        void skipsWhenStatusChanged() {
            ServiceCompletion completion = markedCompleted();
            completion.setMarkedCompletedAt(NOW.minus(Duration.ofHours(49)));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(completion));
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.DISPUTED));

            assertFalse(service.autoConfirm(BOOKING));
            verify(repository, never()).save(any());
        }
    }

    // ------------------------------------------------------------------ get

    @Nested
    @DisplayName("getCompletion")
    class GetCompletion {

        @Test
        @DisplayName("Booking customer and provider can both read the completion")
        void participantsCanRead() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));
            when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(started()));

            assertEquals(BOOKING, service.getCompletion(BOOKING, CUSTOMER).bookingId());
            assertEquals(BOOKING, service.getCompletion(BOOKING, PROVIDER).bookingId());
        }

        @Test
        @DisplayName("Someone outside the booking gets 403 ACCESS_DENIED")
        void strangerIsRejected() {
            when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.IN_PROGRESS));

            assertThrows(AccessDeniedException.class, () -> service.getCompletion(BOOKING, "someone-else"));

            verify(repository, never()).findByBookingId(anyString());
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
