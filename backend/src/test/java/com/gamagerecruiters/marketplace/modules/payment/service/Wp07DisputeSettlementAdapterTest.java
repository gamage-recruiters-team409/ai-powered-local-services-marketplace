package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;

@ExtendWith(MockitoExtension.class)
class Wp07DisputeSettlementAdapterTest {

    private static final String DISPUTE_ID = "dispute-1";
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "customer-1";
    private static final String PROVIDER_ID = "provider-1";
    private static final String PAYMENT_ID = "payment-1";

    @Mock
    private BookingFinancialSnapshotService snapshotService;

    @Mock
    private DisputePayoutHoldRepository holdRepository;

    @Mock
    private DisputePayoutHoldTransactionService holdTransactionService;

    private Wp07DisputeSettlementAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new Wp07DisputeSettlementAdapter(
                snapshotService, holdRepository, holdTransactionService);
    }

    @Test
    void releaseToProvider_returnsAppliedAfterConfirmedRelease() {
        arrangeValidRelease();

        assertEquals(SettlementOutcome.APPLIED,
                adapter.settle(instruction(ResolutionAction.RELEASE_TO_PROVIDER, 0, 0)));

        verify(holdTransactionService).releaseHold(DISPUTE_ID);
    }

    @Test
    void repeatedRelease_isSafeThroughIdempotentTransactionService() {
        arrangeValidRelease();

        SettlementInstruction instruction =
                instruction(ResolutionAction.RELEASE_TO_PROVIDER, 0, 0);

        assertEquals(SettlementOutcome.APPLIED, adapter.settle(instruction));
        assertEquals(SettlementOutcome.APPLIED, adapter.settle(instruction));

        // Both calls delegate to the transaction service, which verifies
        // committed release events and prevents duplicate ledger movements.
        verify(holdTransactionService, times(2)).releaseHold(DISPUTE_ID);
    }

    @Test
    void missingHold_rejectsSettlement() {
        when(snapshotService.getSnapshot(BOOKING_ID)).thenReturn(snapshot());
        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> adapter.settle(
                        instruction(ResolutionAction.RELEASE_TO_PROVIDER, 0, 0)));

        verifyNoInteractions(holdTransactionService);
    }

    @Test
    void mismatchedOwnership_rejectsSettlement() {
        when(snapshotService.getSnapshot(BOOKING_ID)).thenReturn(snapshot());

        DisputePayoutHold wrongHold = new DisputePayoutHold(
                DISPUTE_ID, BOOKING_ID, PAYMENT_ID,
                "different-provider", new BigDecimal("90.00"), "LKR");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.of(wrongHold));

        assertThrows(IllegalStateException.class,
                () -> adapter.settle(
                        instruction(ResolutionAction.RELEASE_TO_PROVIDER, 0, 0)));

        verifyNoInteractions(holdTransactionService);
    }

    @Test
    void refundDecision_rejectsWithoutFinancialWrites() {
        assertThrows(IllegalStateException.class,
                () -> adapter.settle(
                        instruction(ResolutionAction.REFUND_FULL, 100, 30)));

        verifyNoInteractions(
                snapshotService, holdRepository, holdTransactionService);
    }

    @Test
    void invalidIdempotencyKey_rejectsWithoutFinancialWrites() {
        SettlementInstruction invalid = new SettlementInstruction(
                DISPUTE_ID, BOOKING_ID, CUSTOMER_ID, PROVIDER_ID,
                ResolutionAction.RELEASE_TO_PROVIDER, 0, 0, "wrong-key");

        assertThrows(IllegalStateException.class,
                () -> adapter.settle(invalid));

        verifyNoInteractions(
                snapshotService, holdRepository, holdTransactionService);
    }

    @Test
    void unconfirmedRelease_doesNotReportApplied() {
        when(snapshotService.getSnapshot(BOOKING_ID)).thenReturn(snapshot());
        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.of(createHold()));

        when(holdTransactionService.releaseHold(DISPUTE_ID))
                .thenReturn(createHold());

        assertThrows(IllegalStateException.class,
                () -> adapter.settle(
                        instruction(ResolutionAction.RELEASE_TO_PROVIDER, 0, 0)));
    }

    private void arrangeValidRelease() {
        when(snapshotService.getSnapshot(BOOKING_ID)).thenReturn(snapshot());
        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.of(createHold()));

        DisputePayoutHold released = createHold();
        released.markApplied();
        released.markReleased();

        when(holdTransactionService.releaseHold(DISPUTE_ID))
                .thenReturn(released);
    }

    private DisputePayoutHold createHold() {
        return new DisputePayoutHold(
                DISPUTE_ID, BOOKING_ID, PAYMENT_ID,
                PROVIDER_ID, new BigDecimal("90.00"), "LKR");
    }

    private BookingFinancialSnapshotService.FinancialSnapshot snapshot() {
        return new BookingFinancialSnapshotService.FinancialSnapshot(
                PAYMENT_ID, BOOKING_ID, CUSTOMER_ID, PROVIDER_ID,
                "LKR", PaymentStatus.CAPTURED,
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                new BigDecimal("90.00"),
                BigDecimal.ZERO);
    }

    private SettlementInstruction instruction(
            ResolutionAction action, int refundPercent, int penaltyPercent) {

        return new SettlementInstruction(
                DISPUTE_ID, BOOKING_ID, CUSTOMER_ID, PROVIDER_ID,
                action, refundPercent, penaltyPercent,
                "dispute-" + DISPUTE_ID);
    }
}