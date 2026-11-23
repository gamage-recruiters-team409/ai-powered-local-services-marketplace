package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingResolutionPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementRejectedException;
import com.gamagerecruiters.marketplace.modules.dispute.port.stub.StubBookingResolutionAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.port.stub.StubDisputeSettlementAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionStore.SettlementRecord;

/** Settlement order and outcomes; the store is mocked (DisputeResolutionMongoIntegrationTest covers the real one). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeSettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration RECHECK = Duration.ofHours(1);
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private DisputeResolutionStore store;
    @Mock
    private BookingResolutionPort bookingPort;
    @Mock
    private DisputeSettlementPort financePort;

    private DisputeSettlementService service;

    @BeforeEach
    void setUp() {
        service = service(bookingPort, financePort);
        when(store.recordSettlement(any(), any(), any(), any())).thenReturn(true);
        when(store.recordFinanceApplied(any(), any(), any())).thenReturn(true);
    }

    private DisputeSettlementService service(BookingResolutionPort booking, DisputeSettlementPort finance) {
        return new DisputeSettlementService(store, booking, finance, Clock.fixed(NOW, ZoneOffset.UTC), LEASE,
                MAX_ATTEMPTS, Duration.ofMinutes(5), Duration.ofHours(6), RECHECK);
    }

    private Dispute claimed(ResolutionAction action, boolean penalty, SettlementState finance, int attempts) {
        Dispute dispute = new Dispute();
        dispute.setId("d-1");
        dispute.setBookingId("b-1");
        dispute.setCustomerId("c-1");
        dispute.setProviderId("p-1");
        dispute.setStatus(DisputeStatus.RESOLVED_FINAL);
        dispute.setFinalDecision(DisputeDecision.of(action, action == ResolutionAction.REFUND_PARTIAL ? 40 : null,
                penalty, "admin", Role.ADMIN, NOW, "note"));
        DisputeSettlement settlement = DisputeSettlement.pending();
        settlement.setFinanceState(finance);
        settlement.setAttempts(attempts);
        settlement.setLeaseOwner("token-1");
        dispute.setSettlement(settlement);
        when(store.claimSettlement(eq("d-1"), eq(NOW), eq(LEASE), eq(NOW.minus(RECHECK))))
                .thenReturn(Optional.of(dispute));
        return dispute;
    }

    private Dispute release() {
        return claimed(ResolutionAction.RELEASE_TO_PROVIDER, false, SettlementState.PENDING, 0);
    }

    private SettlementRecord recorded() {
        ArgumentCaptor<SettlementRecord> captor = ArgumentCaptor.forClass(SettlementRecord.class);
        verify(store).recordSettlement(eq("d-1"), eq("token-1"), captor.capture(), eq(NOW));
        return captor.getValue();
    }

    // ---------- order ----------

    @Test
    @DisplayName("RELEASE_TO_PROVIDER + finance APPLIED -> finance saved APPLIED, THEN the booking port; same key "
            + "dispute-<id> for both; overall APPLIED")
    void financeFirstThenBooking() {
        release();
        when(financePort.settle(any())).thenReturn(SettlementOutcome.APPLIED);
        when(bookingPort.resolveBooking(any())).thenReturn(SettlementOutcome.APPLIED);

        assertThat(service.settle("d-1")).contains(SettlementState.APPLIED);

        InOrder order = inOrder(financePort, store, bookingPort);
        ArgumentCaptor<SettlementInstruction> financeCall = ArgumentCaptor.forClass(SettlementInstruction.class);
        order.verify(financePort).settle(financeCall.capture());
        order.verify(store).recordFinanceApplied("d-1", "token-1", NOW);
        ArgumentCaptor<SettlementInstruction> bookingCall = ArgumentCaptor.forClass(SettlementInstruction.class);
        order.verify(bookingPort).resolveBooking(bookingCall.capture());
        order.verify(store).recordSettlement(eq("d-1"), eq("token-1"), any(), eq(NOW));
        assertThat(financeCall.getValue().idempotencyKey()).isEqualTo("dispute-d-1");
        assertThat(bookingCall.getValue().idempotencyKey()).isEqualTo("dispute-d-1");
        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.APPLIED, SettlementState.APPLIED,
                SettlementState.APPLIED, false, null, null, null, null));
    }

    @Test
    @DisplayName("the real stubs: finance NOT_APPLIED (WP07_NOT_INTEGRATED), not a failure, booking never called")
    void stubsAreNeverApplied() {
        release();
        BookingResolutionPort bookingStub = org.mockito.Mockito.spy(new StubBookingResolutionAdapter());
        service = service(bookingStub, new StubDisputeSettlementAdapter());

        assertThat(service.settle("d-1")).contains(SettlementState.NOT_APPLIED);

        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.NOT_APPLIED,
                SettlementState.NOT_APPLIED, SettlementState.PENDING, false, null, null,
                DisputeSettlementService.FINANCE_NOT_INTEGRATED, null));
        verifyNoInteractions(bookingStub);
    }

    @Test
    @DisplayName("finance APPLIED, booking stub NOT_APPLIED -> NOT_APPLIED (WP05_NOT_INTEGRATED), finance stays APPLIED")
    void bookingNotIntegrated() {
        release();
        when(financePort.settle(any())).thenReturn(SettlementOutcome.APPLIED);
        when(bookingPort.resolveBooking(any())).thenReturn(SettlementOutcome.NOT_APPLIED);

        service.settle("d-1");

        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.NOT_APPLIED, SettlementState.APPLIED,
                SettlementState.NOT_APPLIED, false, null, null, DisputeSettlementService.BOOKING_NOT_INTEGRATED, null));
    }

    @Test
    @DisplayName("finance already APPLIED (earlier attempt) -> only the booking step runs; settlement never called again")
    void retryRepeatsOnlyBookingStep() {
        claimed(ResolutionAction.RELEASE_TO_PROVIDER, false, SettlementState.APPLIED, 1);
        when(bookingPort.resolveBooking(any())).thenReturn(SettlementOutcome.APPLIED);

        assertThat(service.settle("d-1")).contains(SettlementState.APPLIED);

        verifyNoInteractions(financePort);
        verify(store, never()).recordFinanceApplied(any(), any(), any());
        verify(bookingPort).resolveBooking(any());
    }

    @Test
    @DisplayName("finance APPLIED cannot be saved (lease lost) -> the booking step does NOT run in this attempt")
    void financeAppliedNotSavedStops() {
        release();
        when(financePort.settle(any())).thenReturn(SettlementOutcome.APPLIED);
        when(store.recordFinanceApplied(any(), any(), any())).thenReturn(false);

        service.settle("d-1");

        verifyNoInteractions(bookingPort);
        verify(store, never()).recordSettlement(any(), any(), any(), any());
    }

    // ---------- refunds and penalty (TL decision) ----------

    @ParameterizedTest
    @CsvSource({"REFUND_FULL,false", "REFUND_PARTIAL,false", "REFUND_FULL,true", "REFUND_PARTIAL,true",
            "RELEASE_TO_PROVIDER,true"})
    @DisplayName("refunds and the provider penalty -> MANUAL_REVIEW REFUND_PENDING_INTEGRATION; no port is called, "
            + "not counted as an attempt")
    void refundsGoToManualReview(ResolutionAction action, boolean penalty) {
        claimed(action, penalty, SettlementState.PENDING, 0);

        assertThat(service.settle("d-1")).contains(SettlementState.MANUAL_REVIEW);

        verifyNoInteractions(financePort, bookingPort);
        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.MANUAL_REVIEW, SettlementState.PENDING,
                SettlementState.PENDING, false, null, null, null, DisputeSettlementService.REFUND_PENDING_INTEGRATION));
    }

    // ---------- failures ----------

    @Test
    @DisplayName("finance runtime exception -> FAILED, counted, error code stored, backoff set; booking NOT called")
    void financeFailureIsCounted() {
        release();
        when(financePort.settle(any())).thenThrow(new QueryTimeoutException("socket timeout to wp07-host"));

        assertThat(service.settle("d-1")).contains(SettlementState.FAILED);

        verifyNoInteractions(bookingPort);
        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.FAILED, SettlementState.FAILED,
                SettlementState.PENDING, true, NOW.plus(Duration.ofMinutes(5)), "QueryTimeoutException", null, null));
    }

    @Test
    @DisplayName("an ApiException from a port -> its error code (never the message) is stored")
    void apiErrorCodeStored() {
        release();
        when(financePort.settle(any()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LEDGER_BUSY", "secret detail"));

        service.settle("d-1");

        assertThat(recorded().lastErrorCode()).isEqualTo("LEDGER_BUSY");
    }

    @Test
    @DisplayName("finance APPLIED + booking exception -> FAILED with finance APPLIED and booking FAILED (retry = booking "
            + "only)")
    void bookingFailureKeepsFinanceApplied() {
        release();
        when(financePort.settle(any())).thenReturn(SettlementOutcome.APPLIED);
        when(bookingPort.resolveBooking(any())).thenThrow(new IllegalStateException("booking down"));

        service.settle("d-1");

        SettlementRecord record = recorded();
        assertThat(record.state()).isEqualTo(SettlementState.FAILED);
        assertThat(record.financeState()).isEqualTo(SettlementState.APPLIED);
        assertThat(record.bookingState()).isEqualTo(SettlementState.FAILED);
        assertThat(record.countsAsAttempt()).isTrue();
    }

    @Test
    @DisplayName("the attempt that reaches max-attempts -> MANUAL_REVIEW RETRY_LIMIT_REACHED, no next attempt")
    void retryLimitReached() {
        claimed(ResolutionAction.RELEASE_TO_PROVIDER, false, SettlementState.FAILED, MAX_ATTEMPTS - 1);
        when(financePort.settle(any())).thenThrow(new IllegalStateException("still down"));

        assertThat(service.settle("d-1")).contains(SettlementState.MANUAL_REVIEW);

        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.MANUAL_REVIEW, SettlementState.FAILED,
                SettlementState.PENDING, true, null, "IllegalStateException", null,
                DisputeSettlementService.RETRY_LIMIT_REACHED));
        verifyNoInteractions(bookingPort);
    }

    @Test
    @DisplayName("already at the limit (max-attempts lowered) -> MANUAL_REVIEW RETRY_LIMIT_REACHED without calling ports")
    void alreadyOverLimit() {
        claimed(ResolutionAction.RELEASE_TO_PROVIDER, false, SettlementState.FAILED, MAX_ATTEMPTS + 2);

        assertThat(service.settle("d-1")).contains(SettlementState.MANUAL_REVIEW);

        verifyNoInteractions(financePort, bookingPort);
        assertThat(recorded().manualReviewReason()).isEqualTo(DisputeSettlementService.RETRY_LIMIT_REACHED);
    }

    @Test
    @DisplayName("SettlementRejectedException -> REJECTED with SETTLEMENT_REJECTED:<reasonCode>; not counted; booking "
            + "never called; the message is not stored")
    void permanentRejection() {
        release();
        when(financePort.settle(any())).thenThrow(new SettlementRejectedException("NO_CAPTURED_PAYMENT", "secret"));

        assertThat(service.settle("d-1")).contains(SettlementState.REJECTED);

        assertThat(recorded()).isEqualTo(new SettlementRecord(SettlementState.REJECTED, SettlementState.REJECTED,
                SettlementState.PENDING, false, null, null, null, "SETTLEMENT_REJECTED:NO_CAPTURED_PAYMENT"));
        verifyNoInteractions(bookingPort);
    }

    @Test
    @DisplayName("a rejection reason code that is not a plain code -> stored as UNSPECIFIED")
    void unsafeReasonCode() {
        release();
        when(financePort.settle(any())).thenThrow(new SettlementRejectedException("bad code with spaces <x>", "m"));

        service.settle("d-1");

        assertThat(recorded().manualReviewReason()).isEqualTo("SETTLEMENT_REJECTED:UNSPECIFIED");
    }

    @Test
    @DisplayName("backoff doubles per failed attempt, capped at backoff.max")
    void backoffDoublesAndCaps() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(service.backoff(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(service.backoff(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(service.backoff(30)).isEqualTo(Duration.ofHours(6));
    }

    // ---------- claiming ----------

    @Test
    @DisplayName("nothing due or lease held elsewhere -> no port call")
    void nothingClaimed() {
        when(store.claimSettlement(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.settle("d-1")).isEmpty();
        assertThat(service.settleDue(10)).isZero();
        verifyNoInteractions(financePort, bookingPort);
    }

    @Test
    @DisplayName("the claim itself fails -> settle never throws; the decision stays and is settled later")
    void claimFailureIsDeferred() {
        when(store.claimSettlement(any(), any(), any(), any())).thenThrow(new QueryTimeoutException("down"));

        assertThat(service.settle("d-1")).isEmpty();
        verifyNoInteractions(financePort, bookingPort);
    }

    @Test
    @DisplayName("invalid settings are rejected at startup")
    void invalidSettings() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new DisputeSettlementService(store,
                bookingPort, financePort, Clock.fixed(NOW, ZoneOffset.UTC), LEASE, 0, Duration.ofMinutes(5),
                Duration.ofHours(6), RECHECK))).isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new DisputeSettlementService(store,
                bookingPort, financePort, Clock.fixed(NOW, ZoneOffset.UTC), LEASE, 5, Duration.ofHours(7),
                Duration.ofHours(6), RECHECK))).isInstanceOf(IllegalArgumentException.class);
    }
}
