package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingDisputeStatusPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

@ExtendWith(MockitoExtension.class)
class DisputeServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "cust-1";
    private static final String PROVIDER_ID = "prov-1";
    private static final String DISPUTE_ID = "dispute-1";

    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private BookingLookupPort bookingLookupPort;
    @Mock
    private BookingDisputeStatusPort bookingDisputeStatusPort;
    @Mock
    private PayoutHoldPort payoutHoldPort;

    private DisputeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DisputeServiceImpl(disputeRepository, bookingLookupPort, bookingDisputeStatusPort,
                payoutHoldPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------- helpers ----------

    private static RaiseDisputeRequest raiseRequest() {
        return new RaiseDisputeRequest(BOOKING_ID, DisputeCategory.POOR_QUALITY, "  Work was not finished  ",
                List.of("evidence/photo-1.jpg"));
    }

    private void bookingEndedAgo(Duration ago) {
        when(bookingLookupPort.findBooking(BOOKING_ID))
                .thenReturn(Optional.of(new BookingSnapshot(BOOKING_ID, CUSTOMER_ID, PROVIDER_ID, NOW.minus(ago))));
    }

    private void saveReturnsWithId() {
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(invocation -> {
            Dispute dispute = invocation.getArgument(0);
            dispute.setId(DISPUTE_ID);
            return dispute;
        });
    }

    /** A dispute raised by the customer, awaiting the provider's response. */
    private static Dispute awaitingDispute(Instant responseDueAt) {
        Dispute dispute = new Dispute();
        dispute.setId(DISPUTE_ID);
        dispute.setBookingId(BOOKING_ID);
        dispute.setCustomerId(CUSTOMER_ID);
        dispute.setProviderId(PROVIDER_ID);
        dispute.setRaisedBy(CUSTOMER_ID);
        dispute.setRaisedByRole(Role.CUSTOMER);
        dispute.setCategory(DisputeCategory.NO_SHOW);
        dispute.setDescription("Provider did not come");
        dispute.setCreatedAt(NOW.minus(Duration.ofHours(1)));
        dispute.setResponseDueAt(responseDueAt);
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, CUSTOMER_ID, Role.CUSTOMER, null,
                NOW.minus(Duration.ofHours(1)));
        return dispute;
    }

    private static SubmitDisputeResponseRequest responseRequest() {
        return new SubmitDisputeResponseRequest("I arrived on time", List.of("evidence/gps.png"));
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) thrown;
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getErrorCode()).isEqualTo(code);
    }

    // ---------- raise ----------

    @Test
    @DisplayName("raise: customer within the window creates an AWAITING_RESPONSE dispute with a 72h response deadline")
    void raiseCreatesAwaitingResponseDispute() {
        bookingEndedAgo(Duration.ofHours(10));
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(false);
        saveReturnsWithId();

        DisputeDetailResponse result = service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest());

        assertThat(result.id()).isEqualTo(DISPUTE_ID);
        assertThat(result.status()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
        assertThat(result.responseDueAt()).isEqualTo(NOW.plus(DisputeRules.RESPONSE_WINDOW));
        assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(result.raisedBy()).isEqualTo(CUSTOMER_ID);
        assertThat(result.raisedByRole()).isEqualTo(Role.CUSTOMER);
        assertThat(result.description()).isEqualTo("Work was not finished");
        assertThat(result.evidence()).containsExactly("evidence/photo-1.jpg");
        assertThat(result.createdAt()).isEqualTo(NOW);

        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().getActiveBookingKey()).isEqualTo(BOOKING_ID);

        verify(bookingDisputeStatusPort).markDisputed(BOOKING_ID, DISPUTE_ID);
        verify(payoutHoldPort).holdPayout(BOOKING_ID, DISPUTE_ID);
    }

    @Test
    @DisplayName("raise: the provider of the booking can also raise a dispute")
    void providerCanRaise() {
        bookingEndedAgo(Duration.ofHours(1));
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(false);
        saveReturnsWithId();

        DisputeDetailResponse result = service.raiseDispute(PROVIDER_ID, Role.PROVIDER, raiseRequest());

        assertThat(result.raisedByRole()).isEqualTo(Role.PROVIDER);
    }

    @Test
    @DisplayName("raise: creation adds one status-history entry (null -> AWAITING_RESPONSE) with the raiser as actor")
    void raiseRecordsStatusHistory() {
        bookingEndedAgo(Duration.ofHours(1));
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(false);
        saveReturnsWithId();

        DisputeDetailResponse result = service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest());

        assertThat(result.statusHistory()).hasSize(1);
        DisputeDetailResponse.StatusHistoryView entry = result.statusHistory().get(0);
        assertThat(entry.fromStatus()).isNull();
        assertThat(entry.toStatus()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
        assertThat(entry.actorId()).isEqualTo(CUSTOMER_ID);
        assertThat(entry.actorRole()).isEqualTo(Role.CUSTOMER);
        assertThat(entry.timestamp()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("raise: unknown booking -> 404 BOOKING_NOT_FOUND, nothing saved")
    void raiseUnknownBooking() {
        when(bookingLookupPort.findBooking(BOOKING_ID)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        verify(disputeRepository, never()).save(any());
        verifyNoInteractions(bookingDisputeStatusPort, payoutHoldPort);
    }

    @Test
    @DisplayName("raise: a customer who is not the booking's customer -> 403 NOT_BOOKING_PARTY")
    void raiseByWrongCustomer() {
        bookingEndedAgo(Duration.ofHours(1));

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute("someone-else", Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.FORBIDDEN, "NOT_BOOKING_PARTY");
        verify(disputeRepository, never()).save(any());
    }

    @Test
    @DisplayName("raise: the booking's provider id with the CUSTOMER role is still rejected (role must match side)")
    void raiseRoleMustMatchSide() {
        bookingEndedAgo(Duration.ofHours(1));

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute(PROVIDER_ID, Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.FORBIDDEN, "NOT_BOOKING_PARTY");
    }

    @Test
    @DisplayName("raise: after the 72h window -> 400 DISPUTE_WINDOW_CLOSED")
    void raiseAfterWindow() {
        bookingEndedAgo(DisputeRules.RAISE_WINDOW.plusSeconds(1));

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.BAD_REQUEST, "DISPUTE_WINDOW_CLOSED");
        verify(disputeRepository, never()).save(any());
    }

    @Test
    @DisplayName("raise: exactly at the end of the 72h window is still allowed")
    void raiseAtWindowEdge() {
        bookingEndedAgo(DisputeRules.RAISE_WINDOW);
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(false);
        saveReturnsWithId();

        assertThat(service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest()).status())
                .isEqualTo(DisputeStatus.AWAITING_RESPONSE);
    }

    @Test
    @DisplayName("raise: before the booking slot has ended -> 400 DISPUTE_WINDOW_NOT_OPEN")
    void raiseBeforeSlotEnd() {
        bookingEndedAgo(Duration.ofHours(-2));

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.BAD_REQUEST, "DISPUTE_WINDOW_NOT_OPEN");
    }

    @Test
    @DisplayName("raise: an active dispute already exists -> 409 DISPUTE_ALREADY_EXISTS")
    void raiseDuplicate() {
        bookingEndedAgo(Duration.ofHours(1));
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(true);

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.CONFLICT, "DISPUTE_ALREADY_EXISTS");
        verify(disputeRepository, never()).save(any());
        verifyNoInteractions(bookingDisputeStatusPort, payoutHoldPort);
    }

    @Test
    @DisplayName("raise: concurrent duplicate caught by the unique index -> 409 DISPUTE_ALREADY_EXISTS, ports not called")
    void raiseConcurrentDuplicate() {
        bookingEndedAgo(Duration.ofHours(1));
        when(disputeRepository.existsByActiveBookingKey(BOOKING_ID)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenThrow(new DuplicateKeyException("dup"));

        Throwable thrown = catchThrowable(
                () -> service.raiseDispute(CUSTOMER_ID, Role.CUSTOMER, raiseRequest()));

        assertApiError(thrown, HttpStatus.CONFLICT, "DISPUTE_ALREADY_EXISTS");
        verifyNoInteractions(bookingDisputeStatusPort, payoutHoldPort);
    }

    // ---------- respond ----------

    @Test
    @DisplayName("respond: the other party before the deadline moves the dispute to UNDER_REVIEW and records history")
    void respondMovesToUnderReview() {
        Dispute dispute = awaitingDispute(NOW.plus(Duration.ofHours(5)));
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DisputeDetailResponse result = service.submitResponse(PROVIDER_ID, Role.PROVIDER, DISPUTE_ID, responseRequest());

        assertThat(result.status()).isEqualTo(DisputeStatus.UNDER_REVIEW);
        assertThat(result.response().responderId()).isEqualTo(PROVIDER_ID);
        assertThat(result.response().text()).isEqualTo("I arrived on time");
        assertThat(result.response().evidence()).containsExactly("evidence/gps.png");
        assertThat(result.response().respondedAt()).isEqualTo(NOW);

        List<StatusHistoryEntry> history = dispute.getStatusHistory();
        assertThat(history).hasSize(2);
        StatusHistoryEntry last = history.get(1);
        assertThat(last.getFromStatus()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);
        assertThat(last.getToStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);
        assertThat(last.getActorId()).isEqualTo(PROVIDER_ID);
        assertThat(last.getActorRole()).isEqualTo(Role.PROVIDER);
        assertThat(last.getTimestamp()).isEqualTo(NOW);
        // UNDER_REVIEW is still active, so the booking stays blocked for new disputes.
        assertThat(dispute.getActiveBookingKey()).isEqualTo(BOOKING_ID);
    }

    @Test
    @DisplayName("respond: the party who raised the dispute cannot respond -> 403 NOT_RESPONDING_PARTY")
    void raiserCannotRespond() {
        when(disputeRepository.findById(DISPUTE_ID))
                .thenReturn(Optional.of(awaitingDispute(NOW.plus(Duration.ofHours(5)))));

        Throwable thrown = catchThrowable(
                () -> service.submitResponse(CUSTOMER_ID, Role.CUSTOMER, DISPUTE_ID, responseRequest()));

        assertApiError(thrown, HttpStatus.FORBIDDEN, "NOT_RESPONDING_PARTY");
        verify(disputeRepository, never()).save(any());
    }

    @Test
    @DisplayName("respond: someone outside the dispute -> 403 NOT_DISPUTE_PARTY")
    void outsiderCannotRespond() {
        when(disputeRepository.findById(DISPUTE_ID))
                .thenReturn(Optional.of(awaitingDispute(NOW.plus(Duration.ofHours(5)))));

        Throwable thrown = catchThrowable(
                () -> service.submitResponse("prov-other", Role.PROVIDER, DISPUTE_ID, responseRequest()));

        assertApiError(thrown, HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY");
    }

    @Test
    @DisplayName("respond: at or after responseDueAt -> 400 RESPONSE_WINDOW_CLOSED")
    void lateResponse() {
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(awaitingDispute(NOW)));

        Throwable thrown = catchThrowable(
                () -> service.submitResponse(PROVIDER_ID, Role.PROVIDER, DISPUTE_ID, responseRequest()));

        assertApiError(thrown, HttpStatus.BAD_REQUEST, "RESPONSE_WINDOW_CLOSED");
        verify(disputeRepository, never()).save(any());
    }

    @Test
    @DisplayName("respond: not in AWAITING_RESPONSE -> 409 INVALID_DISPUTE_STATUS")
    void respondWrongStatus() {
        Dispute dispute = awaitingDispute(NOW.plus(Duration.ofHours(5)));
        dispute.changeStatus(DisputeStatus.UNDER_REVIEW, PROVIDER_ID, Role.PROVIDER, null, NOW);
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));

        Throwable thrown = catchThrowable(
                () -> service.submitResponse(PROVIDER_ID, Role.PROVIDER, DISPUTE_ID, responseRequest()));

        assertApiError(thrown, HttpStatus.CONFLICT, "INVALID_DISPUTE_STATUS");
    }

    @Test
    @DisplayName("respond: concurrent update (optimistic lock) -> 409 DISPUTE_CONCURRENT_UPDATE")
    void respondConcurrentUpdate() {
        when(disputeRepository.findById(DISPUTE_ID))
                .thenReturn(Optional.of(awaitingDispute(NOW.plus(Duration.ofHours(5)))));
        when(disputeRepository.save(any(Dispute.class))).thenThrow(new OptimisticLockingFailureException("stale"));

        Throwable thrown = catchThrowable(
                () -> service.submitResponse(PROVIDER_ID, Role.PROVIDER, DISPUTE_ID, responseRequest()));

        assertApiError(thrown, HttpStatus.CONFLICT, "DISPUTE_CONCURRENT_UPDATE");
    }

    @Test
    @DisplayName("respond: unknown dispute -> 404 DISPUTE_NOT_FOUND")
    void respondUnknownDispute() {
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitResponse(PROVIDER_ID, Role.PROVIDER, DISPUTE_ID, responseRequest()))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("DISPUTE_NOT_FOUND");
    }

    // ---------- read ----------

    @Test
    @DisplayName("getDisputeForParty: both parties can read; anyone else -> 403")
    void partyRead() {
        when(disputeRepository.findById(DISPUTE_ID))
                .thenReturn(Optional.of(awaitingDispute(NOW.plus(Duration.ofHours(5)))));

        assertThat(service.getDisputeForParty(CUSTOMER_ID, DISPUTE_ID).id()).isEqualTo(DISPUTE_ID);
        assertThat(service.getDisputeForParty(PROVIDER_ID, DISPUTE_ID).id()).isEqualTo(DISPUTE_ID);

        Throwable thrown = catchThrowable(
                () -> service.getDisputeForParty("cust-other", DISPUTE_ID));
        assertApiError(thrown, HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY");
    }

    @Test
    @DisplayName("getMyDisputes: queries by party, newest first, and maps to summaries")
    void myDisputes() {
        Pageable expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Dispute> page = new PageImpl<>(List.of(awaitingDispute(NOW)), expected, 1);
        when(disputeRepository.findByParty(CUSTOMER_ID, expected)).thenReturn(page);

        DisputePageResponse<DisputeSummaryResponse> result = service.getMyDisputes(CUSTOMER_ID, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo(DISPUTE_ID);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("paging: invalid page or size -> 400 INVALID_PAGE_REQUEST")
    void invalidPaging() {
        assertApiError(catchThrowable(() -> service.getMyDisputes(CUSTOMER_ID, -1, 20)),
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST");
        assertApiError(catchThrowable(() -> service.getMyDisputes(CUSTOMER_ID, 0, 0)),
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST");
        assertApiError(catchThrowable(
                        () -> service.getMyDisputes(CUSTOMER_ID, 0, DisputeRules.MAX_PAGE_SIZE + 1)),
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST");
    }

    @Test
    @DisplayName("admin list: filters by status (case-insensitive); no status lists all")
    void adminList() {
        when(disputeRepository.findByStatus(eq(DisputeStatus.UNDER_REVIEW), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(disputeRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.getDisputesForAdmin("under_review", 0, 20);
        service.getDisputesForAdmin(null, 0, 20);

        verify(disputeRepository).findByStatus(eq(DisputeStatus.UNDER_REVIEW), any(Pageable.class));
        verify(disputeRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("admin list: unknown status -> 400 INVALID_STATUS_FILTER")
    void adminListUnknownStatus() {
        assertApiError(catchThrowable(() -> service.getDisputesForAdmin("BANANA", 0, 20)),
                HttpStatus.BAD_REQUEST, "INVALID_STATUS_FILTER");
        verify(disputeRepository, never()).findByStatus(any(), any());
    }

    @Test
    @DisplayName("domain: leaving the active statuses clears activeBookingKey so a new dispute can be raised")
    void activeBookingKeyClearedWhenInactive() {
        Dispute dispute = awaitingDispute(NOW);
        dispute.changeStatus(DisputeStatus.CLOSED, "admin-1", Role.ADMIN, "Withdrawn by customer", NOW);

        assertThat(dispute.getActiveBookingKey()).isNull();
        StatusHistoryEntry last = dispute.getStatusHistory().get(dispute.getStatusHistory().size() - 1);
        assertThat(last.getReason()).isEqualTo("Withdrawn by customer");
        assertThat(last.getActorRole()).isEqualTo(Role.ADMIN);
        assertThatThrownBy(() -> dispute.getStatusHistory().add(last)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("admin detail: unknown id -> 404")
    void adminDetailUnknown() {
        when(disputeRepository.findById(anyString())).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.getDisputeForAdmin("nope")),
                HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND");
    }
}
