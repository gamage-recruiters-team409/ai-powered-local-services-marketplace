
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold.HoldStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Unit tests for booking-specific dispute payout hold coordination.
 *
 * These tests verify service decisions and collaborator calls.
 * Real MongoDB transaction atomicity and concurrency require
 * separate integration tests.
 */
class DisputePayoutHoldTransactionServiceTest {

    private DisputePayoutHoldRepository holdRepository;
    private EarningsLedgerRepository ledgerRepository;
    private EarningsBalanceCalculator balanceCalculator;
    private EarningsLedgerTransactionService ledgerTransactionService;
    private PlatformTransactionManager transactionManager;

    private DisputePayoutHoldTransactionService service;

    private DisputePayoutHold hold;

    @BeforeEach
    void setUp() {

        holdRepository = mock(DisputePayoutHoldRepository.class);
        ledgerRepository = mock(EarningsLedgerRepository.class);
        balanceCalculator = mock(EarningsBalanceCalculator.class);
        ledgerTransactionService = mock(EarningsLedgerTransactionService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        // Allow TransactionTemplate to execute callbacks in unit tests.
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        service = new DisputePayoutHoldTransactionService(
                holdRepository,
                ledgerRepository,
                balanceCalculator,
                ledgerTransactionService,
                transactionManager);

        hold = new DisputePayoutHold(
                "dispute-001",
                "booking-001",
                "payment-001",
                "provider-001",
                new BigDecimal("80.00"),
                "LKR");
    }

    /**
     * A valid hold should append one financial event and
     * transition from PENDING to APPLIED.
     */
    @Test
    void applyHold_validBooking_appliesOnce() {

        arrangeValidPendingHold();

        when(holdRepository.save(any(DisputePayoutHold.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DisputePayoutHold result = service.applyHold("dispute-001");

        assertSame(hold, result);
        assertEquals(HoldStatus.APPLIED, result.getStatus());

        verify(ledgerTransactionService, times(1))
                .appendAtomically(any(EarningsLedgerEntry.class));

        verify(holdRepository, times(1))
                .save(hold);

        verify(transactionManager).commit(any());
    }

    /**
     * Repeating an already applied hold must not append
     * another ledger event.
     */
    @Test
    void applyHold_alreadyApplied_isIdempotent() {

        hold.markApplied();

        when(holdRepository.findByDisputeId("dispute-001"))
                .thenReturn(Optional.of(hold));

        when(ledgerRepository.findByEventKey(
                "DISPUTE_HOLD:dispute-001"))
                .thenReturn(Optional.of(
                        createLedgerEntry(
                                EarningsLedgerEventType.EARNING_HOLD,
                                new BigDecimal("80.00"))));

        DisputePayoutHold result = service.applyHold("dispute-001");

        assertSame(hold, result);
        assertEquals(HoldStatus.APPLIED, result.getStatus());

        verifyNoInteractions(ledgerTransactionService);
        verify(holdRepository, never()).save(any());
    }

    /**
     * A booking with insufficient available earnings
     * must not create a financial hold.
     */
    @Test
    void applyHold_insufficientBookingEarnings_rejects() {

        when(holdRepository.findByDisputeId("dispute-001"))
                .thenReturn(Optional.of(hold));

        when(holdRepository.findByBookingId("booking-001"))
                .thenReturn(List.of(hold));

        when(ledgerRepository.findByBookingIdOrderByCreatedAtDesc(
                "booking-001"))
                .thenReturn(List.of(
                        createLedgerEntry(
                                EarningsLedgerEventType.EARNING_AVAILABLE,
                                new BigDecimal("40.00"))));

        when(balanceCalculator.calculate(any()))
                .thenReturn(new EarningsBalanceCalculator.EarningsBalance(
                        BigDecimal.ZERO,
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));

        assertThrows(IllegalStateException.class,
                () -> service.applyHold("dispute-001"));

        assertEquals(HoldStatus.PENDING, hold.getStatus());

        verifyNoInteractions(ledgerTransactionService);
        verify(holdRepository, never()).save(any());
    }

    /**
     * A second active dispute for the same booking
     * must not reserve the same funds again.
     */
    @Test
    void applyHold_conflictingDispute_rejects() {

        DisputePayoutHold anotherHold = new DisputePayoutHold(
                "dispute-002",
                "booking-001",
                "payment-001",
                "provider-001",
                new BigDecimal("80.00"),
                "LKR");

        anotherHold.markApplied();

        when(holdRepository.findByDisputeId("dispute-001"))
                .thenReturn(Optional.of(hold));

        when(holdRepository.findByBookingId("booking-001"))
                .thenReturn(List.of(hold, anotherHold));

        assertThrows(IllegalStateException.class,
                () -> service.applyHold("dispute-001"));

        assertEquals(HoldStatus.PENDING, hold.getStatus());

        verifyNoInteractions(ledgerTransactionService);
        verify(holdRepository, never()).save(any());
    }

    /**
     * A ledger failure must prevent the hold status
     * from transitioning to APPLIED.
     */
    @Test
    void applyHold_ledgerFailure_doesNotApplyHold() {

        arrangeValidPendingHold();

        when(ledgerTransactionService.appendAtomically(
                any(EarningsLedgerEntry.class)))
                .thenThrow(new IllegalStateException(
                        "Simulated ledger failure"));

        assertThrows(IllegalStateException.class,
                () -> service.applyHold("dispute-001"));

        assertEquals(HoldStatus.PENDING, hold.getStatus());

        verify(holdRepository, never()).save(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * Arranges a pending hold with sufficient booking earnings.
     */
    private void arrangeValidPendingHold() {

        when(holdRepository.findByDisputeId("dispute-001"))
                .thenReturn(Optional.of(hold));

        when(holdRepository.findByBookingId("booking-001"))
                .thenReturn(List.of(hold));

        when(ledgerRepository.findByBookingIdOrderByCreatedAtDesc(
                "booking-001"))
                .thenReturn(List.of(
                        createLedgerEntry(
                                EarningsLedgerEventType.EARNING_AVAILABLE,
                                new BigDecimal("80.00"))));

        when(balanceCalculator.calculate(any()))
                .thenReturn(new EarningsBalanceCalculator.EarningsBalance(
                        BigDecimal.ZERO,
                        new BigDecimal("80.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));
    }

    /**
     * Creates a valid ledger event for mocked repository results.
     */
    private EarningsLedgerEntry createLedgerEntry(
            EarningsLedgerEventType type,
            BigDecimal amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey("DISPUTE_HOLD:dispute-001");
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId("provider-001");
        entry.setEventType(type);
        entry.setAmount(amount);
        entry.setCurrency("LKR");
        entry.setSequenceNumber(1L);
        entry.setCreatedAt(Instant.now());

        return entry;
    }
}
