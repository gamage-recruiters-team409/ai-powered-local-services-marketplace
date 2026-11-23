package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Tests split dispute reservations and source-preserving releases.
 * MongoDB commit atomicity requires separate integration tests.
 */
class DisputePayoutHoldSplitReleaseTest {

    private DisputePayoutHoldRepository holdRepository;
    private EarningsLedgerRepository ledgerRepository;
    private EarningsBalanceCalculator balanceCalculator;
    private EarningsLedgerTransactionService ledgerService;
    private PlatformTransactionManager transactionManager;

    private DisputePayoutHoldTransactionService service;
    private DisputePayoutHold hold;

    private static final String DISPUTE = "dispute-001";
    private static final String HOLD_KEY = "DISPUTE_HOLD:" + DISPUTE;
    private static final String RELEASE_KEY = "DISPUTE_RELEASE:" + DISPUTE;

    @BeforeEach
    void setUp() {
        holdRepository = mock(DisputePayoutHoldRepository.class);
        ledgerRepository = mock(EarningsLedgerRepository.class);
        balanceCalculator = mock(EarningsBalanceCalculator.class);
        ledgerService = mock(EarningsLedgerTransactionService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        service = new DisputePayoutHoldTransactionService(
                holdRepository, ledgerRepository, balanceCalculator,
                ledgerService, transactionManager);

        hold = new DisputePayoutHold(
                DISPUTE, "booking-001", "payment-001",
                "provider-001", new BigDecimal("80.00"), "LKR");
    }

    @Test
    void pendingOnlyHold_usesPendingEvent() {
        arrangePendingHold("80.00", "0.00");

        service.applyHold(DISPUTE);

        ArgumentCaptor<EarningsLedgerEntry> captor =
                ArgumentCaptor.forClass(EarningsLedgerEntry.class);

        verify(ledgerService).appendAtomically(captor.capture());

        assertEquals(EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                captor.getValue().getEventType());
        assertEquals(HOLD_KEY, captor.getValue().getEventKey());
        assertEquals(0, captor.getValue().getAmount()
                .compareTo(new BigDecimal("80.00")));
    }

    @Test
    void availableOnlyHold_usesAvailableEvent() {
        arrangePendingHold("0.00", "80.00");

        service.applyHold(DISPUTE);

        ArgumentCaptor<EarningsLedgerEntry> captor =
                ArgumentCaptor.forClass(EarningsLedgerEntry.class);

        verify(ledgerService).appendAtomically(captor.capture());

        assertEquals(EarningsLedgerEventType.EARNING_HOLD,
                captor.getValue().getEventType());
        assertEquals(HOLD_KEY, captor.getValue().getEventKey());
    }

    @Test
    void mixedHold_createsTwoSourceSpecificEvents() {
        arrangePendingHold("30.00", "50.00");

        service.applyHold(DISPUTE);

        ArgumentCaptor<EarningsLedgerEntry> captor =
                ArgumentCaptor.forClass(EarningsLedgerEntry.class);

        verify(ledgerService, times(2))
                .appendAtomically(captor.capture());

        List<EarningsLedgerEntry> entries = captor.getAllValues();

        assertEquals(EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                entries.get(0).getEventType());
        assertEquals(HOLD_KEY, entries.get(0).getEventKey());
        assertEquals(0, entries.get(0).getAmount()
                .compareTo(new BigDecimal("30.00")));

        assertEquals(EarningsLedgerEventType.EARNING_HOLD,
                entries.get(1).getEventType());
        assertEquals(HOLD_KEY + ":AVAILABLE", entries.get(1).getEventKey());
        assertEquals(0, entries.get(1).getAmount()
                .compareTo(new BigDecimal("50.00")));

        assertEquals(DisputePayoutHold.HoldStatus.APPLIED, hold.getStatus());
    }

    @Test
    void mixedRelease_restoresOriginalSources() {
        hold.markApplied();

        when(holdRepository.findByDisputeId(DISPUTE))
                .thenReturn(Optional.of(hold));

        when(ledgerRepository.findByEventKey(HOLD_KEY))
                .thenReturn(Optional.of(entry(
                        HOLD_KEY,
                        EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                        "30.00")));

        when(ledgerRepository.findByEventKey(HOLD_KEY + ":AVAILABLE"))
                .thenReturn(Optional.of(entry(
                        HOLD_KEY + ":AVAILABLE",
                        EarningsLedgerEventType.EARNING_HOLD,
                        "50.00")));

        when(holdRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.releaseHold(DISPUTE);

        ArgumentCaptor<EarningsLedgerEntry> captor =
                ArgumentCaptor.forClass(EarningsLedgerEntry.class);

        verify(ledgerService, times(2))
                .appendAtomically(captor.capture());

        List<EarningsLedgerEntry> releases = captor.getAllValues();

        assertEquals(EarningsLedgerEventType.EARNING_RELEASE_TO_PENDING,
                releases.get(0).getEventType());
        assertEquals(RELEASE_KEY, releases.get(0).getEventKey());

        assertEquals(EarningsLedgerEventType.EARNING_RELEASE,
                releases.get(1).getEventType());
        assertEquals(RELEASE_KEY + ":AVAILABLE",
                releases.get(1).getEventKey());

        assertEquals(DisputePayoutHold.HoldStatus.RELEASED,
                hold.getStatus());
    }

    @Test
    void repeatedRelease_doesNotAppendAgain() {
        hold.markApplied();
        hold.markReleased();

        when(holdRepository.findByDisputeId(DISPUTE))
                .thenReturn(Optional.of(hold));

        when(ledgerRepository.findByEventKey(HOLD_KEY))
                .thenReturn(Optional.of(entry(
                        HOLD_KEY,
                        EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                        "80.00")));

        when(ledgerRepository.findByEventKey(RELEASE_KEY))
                .thenReturn(Optional.of(entry(
                        RELEASE_KEY,
                        EarningsLedgerEventType.EARNING_RELEASE_TO_PENDING,
                        "80.00")));

        DisputePayoutHold result = service.releaseHold(DISPUTE);

        assertSame(hold, result);
        verifyNoInteractions(ledgerService);
        verify(holdRepository, never()).save(any());
    }

    @Test
    void mismatchedReleaseEvent_rejectsIdempotentRetry() {
        hold.markApplied();
        hold.markReleased();

        when(holdRepository.findByDisputeId(DISPUTE))
                .thenReturn(Optional.of(hold));

        when(ledgerRepository.findByEventKey(HOLD_KEY))
                .thenReturn(Optional.of(entry(
                        HOLD_KEY,
                        EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                        "80.00")));

        when(ledgerRepository.findByEventKey(RELEASE_KEY))
                .thenReturn(Optional.of(entry(
                        RELEASE_KEY,
                        EarningsLedgerEventType.EARNING_RELEASE_TO_PENDING,
                        "70.00")));

        assertThrows(IllegalStateException.class,
                () -> service.releaseHold(DISPUTE));

        verifyNoInteractions(ledgerService);
    }

    private void arrangePendingHold(String pending, String available) {
        when(holdRepository.findByDisputeId(DISPUTE))
                .thenReturn(Optional.of(hold));

        when(holdRepository.findByBookingId("booking-001"))
                .thenReturn(List.of(hold));

        when(ledgerRepository.findByBookingIdOrderByCreatedAtDesc(
                "booking-001"))
                .thenReturn(List.of(entry(
                        "EARNING_PENDING:booking-001",
                        EarningsLedgerEventType.EARNING_PENDING,
                        "80.00")));

        when(balanceCalculator.calculate(any()))
                .thenReturn(new EarningsBalanceCalculator.EarningsBalance(
                        new BigDecimal(pending),
                        new BigDecimal(available),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));

        when(holdRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private EarningsLedgerEntry entry(
            String key, EarningsLedgerEventType type, String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();
        entry.setEventKey(key);
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId("provider-001");
        entry.setCurrency("LKR");
        entry.setEventType(type);
        entry.setAmount(new BigDecimal(amount));
        entry.setSequenceNumber(1L);
        entry.setCreatedAt(Instant.now());

        return entry;
    }
}