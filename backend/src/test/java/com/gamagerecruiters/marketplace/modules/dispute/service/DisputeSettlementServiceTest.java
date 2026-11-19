package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;

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
import com.gamagerecruiters.marketplace.modules.dispute.port.stub.StubBookingResolutionAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.port.stub.StubDisputeSettlementAdapter;

/** Settlement outcomes; the store is mocked (the real-MongoDB test covers the atomic parts). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeSettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    @Mock
    private DisputeResolutionStore store;
    @Mock
    private BookingResolutionPort bookingPort;
    @Mock
    private DisputeSettlementPort payoutPort;

    private DisputeSettlementService service;

    @BeforeEach
    void setUp() {
        service = new DisputeSettlementService(store, bookingPort, payoutPort, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5), Duration.ofMinutes(15), 10);
        when(store.recordSettlement(any(), any(), any(), any(), any())).thenReturn(true);
    }

    private Dispute claimed(SettlementState booking, SettlementState payout) {
        Dispute dispute = new Dispute();
        dispute.setId("d-1");
        dispute.setBookingId("b-1");
        dispute.setCustomerId("c-1");
        dispute.setProviderId("p-1");
        dispute.setStatus(DisputeStatus.RESOLVED_FINAL);
        dispute.setFinalDecision(DisputeDecision.of(ResolutionAction.REFUND_PARTIAL, 40, true, "admin", Role.ADMIN,
                NOW, "note"));
        DisputeSettlement settlement = DisputeSettlement.pending();
        settlement.setBookingState(booking);
        settlement.setPayoutState(payout);
        settlement.setLeaseOwner("token-1");
        dispute.setSettlement(settlement);
        when(store.claimSettlement(eq("d-1"), eq(NOW), any(), any(), anyInt())).thenReturn(Optional.of(dispute));
        return dispute;
    }

    @Test
    @DisplayName("the real stubs report NOT_APPLIED (never a success); the instruction has ids, percents and a stable key")
    void stubsAreNeverApplied() {
        claimed(SettlementState.PENDING, SettlementState.PENDING);
        service = new DisputeSettlementService(store, new StubBookingResolutionAdapter(),
                new StubDisputeSettlementAdapter(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5),
                Duration.ofMinutes(15), 10);

        assertThat(service.settle("d-1")).contains(SettlementState.NOT_APPLIED);
        verify(store).recordSettlement("d-1", "token-1", SettlementState.NOT_APPLIED, SettlementState.NOT_APPLIED, NOW);
    }

    @Test
    @DisplayName("a port that throws (failure or uncertain outcome) -> FAILED for that part only")
    void failureIsRecorded() {
        claimed(SettlementState.PENDING, SettlementState.PENDING);
        when(bookingPort.resolveBooking(any())).thenReturn(SettlementOutcome.APPLIED);
        when(payoutPort.settle(any())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(service.settle("d-1")).contains(SettlementState.FAILED);
        verify(store).recordSettlement("d-1", "token-1", SettlementState.APPLIED, SettlementState.FAILED, NOW);
    }

    @Test
    @DisplayName("a retry calls only the parts that are PENDING or FAILED, with the same idempotency key")
    void retryOnlyFailedPart() {
        claimed(SettlementState.APPLIED, SettlementState.FAILED);
        when(payoutPort.settle(any())).thenReturn(SettlementOutcome.APPLIED);

        assertThat(service.settle("d-1")).contains(SettlementState.APPLIED);

        verify(bookingPort, never()).resolveBooking(any());
        ArgumentCaptor<SettlementInstruction> instruction = ArgumentCaptor.forClass(SettlementInstruction.class);
        verify(payoutPort).settle(instruction.capture());
        assertThat(instruction.getValue().idempotencyKey()).isEqualTo("dispute-d-1");
        assertThat(instruction.getValue().customerRefundPercent()).isEqualTo(40);
        assertThat(instruction.getValue().providerPenaltyPercent()).isEqualTo(30);
    }

    @Test
    @DisplayName("nothing to claim (another worker holds it, or already done) -> no port call")
    void notClaimed() {
        when(store.claimSettlement(any(), any(), any(), any(), anyInt())).thenReturn(Optional.empty());

        assertThat(service.settle("d-1")).isEmpty();
        verify(bookingPort, never()).resolveBooking(any());
        verify(payoutPort, never()).settle(any());
    }

    @Test
    @DisplayName("the store fails while claiming -> no exception reaches the caller (the decision stays)")
    void claimFailureIsSwallowed() {
        when(store.claimSettlement(any(), any(), any(), any(), anyInt())).thenThrow(new QueryTimeoutException("down"));

        assertThat(service.settle("d-1")).isEmpty();
    }

    @Test
    @DisplayName("overall state: APPLIED only if both applied; FAILED wins; NOT_APPLIED otherwise")
    void overallState() {
        assertThat(DisputeSettlement.overall(SettlementState.APPLIED, SettlementState.APPLIED))
                .isEqualTo(SettlementState.APPLIED);
        assertThat(DisputeSettlement.overall(SettlementState.NOT_APPLIED, SettlementState.FAILED))
                .isEqualTo(SettlementState.FAILED);
        assertThat(DisputeSettlement.overall(SettlementState.APPLIED, SettlementState.NOT_APPLIED))
                .isEqualTo(SettlementState.NOT_APPLIED);
    }
}
