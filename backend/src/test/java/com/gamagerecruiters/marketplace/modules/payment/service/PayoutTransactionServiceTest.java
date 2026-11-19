package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutAllocation;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutAllocationRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutRepository;

class PayoutTransactionServiceTest {

    private PayoutRepository payoutRepository;
    private PayoutAllocationRepository allocationRepository;
    private EarningsLedgerRepository ledgerRepository;
    private EarningsLedgerService earningsLedgerService;
    private BookingPayoutAllocationCalculator allocationCalculator;
    private BookingFinancialSnapshotService snapshotService;
    private DisputePayoutHoldRepository holdRepository;
    private BookingFacade bookingFacade;
    private CompletionLookup completionLookup;
    private PayoutTransactionService service;

    @BeforeEach
    void setUp() {
        payoutRepository = mock(PayoutRepository.class);
        allocationRepository = mock(PayoutAllocationRepository.class);
        ledgerRepository = mock(EarningsLedgerRepository.class);
        earningsLedgerService = mock(EarningsLedgerService.class);
        allocationCalculator = mock(BookingPayoutAllocationCalculator.class);
        snapshotService = mock(BookingFinancialSnapshotService.class);
        holdRepository = mock(DisputePayoutHoldRepository.class);
        bookingFacade = mock(BookingFacade.class);
        completionLookup = mock(CompletionLookup.class);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        service = new PayoutTransactionService(
                payoutRepository, allocationRepository, ledgerRepository,
                earningsLedgerService, allocationCalculator, snapshotService,
                holdRepository, bookingFacade, completionLookup, transactionManager);
    }

    @Test
    void reserveRejectsActiveDisputeHold() {
        Payout payout = payoutRequest();

        when(payoutRepository.findByRequestKey("payout-1")).thenReturn(Optional.empty());
        when(payoutRepository.save(any(Payout.class))).thenAnswer(invocation -> {
            Payout saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", "payout-db-1");
            return saved;
        });
        when(ledgerRepository.findByProviderIdOrderByCreatedAtDesc("provider-1"))
                .thenReturn(List.of(ledgerEntry()));
        when(snapshotService.getSnapshot("booking-1")).thenReturn(snapshot());
        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(participants());
        when(completionLookup.findConfirmedAt("booking-1"))
                .thenReturn(Optional.of(Instant.parse("2026-11-11T10:00:00Z")));

        DisputePayoutHold hold = new DisputePayoutHold(
                "dispute-1", "booking-1", "payment-1", "provider-1",
                new BigDecimal("5000.00"), "LKR");
        hold.markApplied();
        when(holdRepository.findByBookingId("booking-1")).thenReturn(List.of(hold));

        assertThrows(IllegalStateException.class, () -> service.reserve(payout));
    }

    @Test
    void paidRequiresProcessingAndBankReference() {
        Payout payout = payoutRequest();
        ReflectionTestUtils.setField(payout, "id", "payout-db-1");
        payout.markProcessing("admin-1");

        PayoutAllocation allocation = new PayoutAllocation(
                "payout-db-1", "provider-1", "payment-1", "booking-1",
                new BigDecimal("5000.00"), "LKR");

        when(payoutRepository.findById("payout-db-1")).thenReturn(Optional.of(payout));
        when(allocationRepository.findByPayoutId("payout-db-1")).thenReturn(List.of(allocation));

        assertThrows(IllegalArgumentException.class,
                () -> service.markPaid("payout-db-1", "admin-1", " "));
        assertEquals(PayoutStatus.PROCESSING, payout.getStatus());
    }

    @Test
    void failedPayoutAppendsReleaseEventAndRestoresStatus() {
        Payout payout = payoutRequest();
        ReflectionTestUtils.setField(payout, "id", "payout-db-1");
        payout.markProcessing("admin-1");

        PayoutAllocation allocation = new PayoutAllocation(
                "payout-db-1", "provider-1", "payment-1", "booking-1",
                new BigDecimal("5000.00"), "LKR");

        when(payoutRepository.findById("payout-db-1")).thenReturn(Optional.of(payout));
        when(allocationRepository.findByPayoutId("payout-db-1")).thenReturn(List.of(allocation));
        when(payoutRepository.save(any(Payout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payout result = service.markFailed(
                "payout-db-1", "admin-1", "bank transfer rejected");

        assertEquals(PayoutStatus.FAILED, result.getStatus());
        verify(earningsLedgerService).appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void reserveFailsClosedWhenPayoutMaturityContractIsUnresolved() {
        Payout payout = payoutRequest();

        when(payoutRepository.findByRequestKey("payout-1")).thenReturn(Optional.empty());
        when(payoutRepository.save(any(Payout.class))).thenAnswer(invocation -> {
            Payout saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", "payout-db-1");
            return saved;
        });
        when(ledgerRepository.findByProviderIdOrderByCreatedAtDesc("provider-1"))
                .thenReturn(List.of(ledgerEntry()));
        when(snapshotService.getSnapshot("booking-1")).thenReturn(snapshot());
        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(participants());
        when(completionLookup.findConfirmedAt("booking-1"))
                .thenReturn(Optional.of(Instant.parse("2026-11-11T10:00:00Z")));
        when(holdRepository.findByBookingId("booking-1")).thenReturn(List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> service.reserve(payout));

        assertEquals(
                "Payout blocked: approved 72-hour payout maturity contract is unresolved for booking booking-1",
                exception.getMessage());
    }

    private Payout payoutRequest() {
        return new Payout("payout-1", "provider-1",
                new BigDecimal("5000.00"), "LKR");
    }

    private EarningsLedgerEntry ledgerEntry() {
        EarningsLedgerEntry entry = new EarningsLedgerEntry();
        entry.setEventKey("earning-pending:payment-1");
        entry.setPaymentId("payment-1");
        entry.setBookingId("booking-1");
        entry.setProviderId("provider-1");
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setAmount(new BigDecimal("5000.00"));
        entry.setCurrency("LKR");
        entry.setSequenceNumber(1L);
        entry.setCreatedAt(Instant.parse("2026-11-10T10:00:00Z"));
        return entry;
    }

    private BookingFinancialSnapshotService.FinancialSnapshot snapshot() {
        return new BookingFinancialSnapshotService.FinancialSnapshot(
                "payment-1", "booking-1", "customer-1", "provider-1",
                "LKR", PaymentStatus.CAPTURED,
                new BigDecimal("6000.00"), new BigDecimal("1000.00"),
                new BigDecimal("5000.00"), BigDecimal.ZERO);
    }

    private BookingParticipants participants() {
        return new BookingParticipants(
                "booking-1", null, "listing-1", "customer-1", "provider-1",
                BookingStatus.COMPLETED,
                Instant.parse("2026-11-10T10:00:00Z"),
                Instant.parse("2026-11-10T11:00:00Z"));
    }
}
