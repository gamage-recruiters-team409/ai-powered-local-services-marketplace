package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
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

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

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
                holdRepository, bookingFacade, completionLookup,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(72));
    }

    @Test
    void reserveRejectsInsufficientEligibleEarnings() {
        Payout payout = payoutRequest();

        when(payoutRepository.findByRequestKey("payout-1"))
                .thenReturn(Optional.empty());

        when(payoutRepository.save(any(Payout.class)))
                .thenAnswer(invocation -> {
                    Payout saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", "payout-db-1");
                    return saved;
                });

        when(ledgerRepository.findByProviderIdOrderByCreatedAtDesc("provider-1"))
                .thenReturn(List.of());

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.reserve(payout));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ELIGIBLE_EARNINGS",
                exception.getErrorCode());
        assertEquals("Insufficient eligible booking earnings for payout",
                exception.getMessage());
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

        ApiException exception = assertThrows(
                ApiException.class, () -> service.reserve(payout));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ELIGIBLE_EARNINGS", exception.getErrorCode());
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
    void customerConfirmedPayoutIsRejectedBefore72HourBoundary() {
        Payout payout = arrangeSuccessfulReservationInputs(NOW.minus(Duration.ofHours(71)));

        ApiException exception = assertThrows(
                ApiException.class, () -> service.reserve(payout));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ELIGIBLE_EARNINGS", exception.getErrorCode());
    }

    @Test
    void customerConfirmedPayoutIsAllowedExactlyAt72HourBoundary() {
        Payout payout = arrangeSuccessfulReservationInputs(NOW.minus(Duration.ofHours(72)));

        Payout result = service.reserve(payout);

        assertEquals("payout-db-1", result.getId());
        verify(allocationRepository).save(any(PayoutAllocation.class));
    }

    @Test
    void autoConfirmedPayoutIsAllowedAfter72HourBoundary() {
        Payout payout = arrangeSuccessfulReservationInputs(NOW.minus(Duration.ofHours(73)));

        Payout result = service.reserve(payout);

        assertEquals("payout-db-1", result.getId());
        verify(earningsLedgerService).appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void reserveRejectsMissingCompletionConfirmationTimestamp() {
        Payout payout = arrangeBaseReservationMocks();
        when(completionLookup.findConfirmedAt("booking-1")).thenReturn(Optional.empty());

        ApiException exception = assertThrows(
                ApiException.class, () -> service.reserve(payout));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ELIGIBLE_EARNINGS", exception.getErrorCode());
    }

    @Test
    void reserveRejectsFutureCompletionConfirmationTimestamp() {
        Payout payout = arrangeSuccessfulReservationInputs(NOW.plus(Duration.ofMinutes(1)));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> service.reserve(payout));

        assertEquals(
                "Payout blocked: completion confirmation is in the future for booking booking-1",
                exception.getMessage());
    }

    @Test
    void reserveSkipsIneligibleBookingsAndUsesMatureEarnings() {
        arrangeMixedBookingReservation(new BigDecimal("8000.00"));

        Payout result = service.reserve(payoutRequest());

        assertEquals("payout-db-1", result.getId());

        org.mockito.ArgumentCaptor<PayoutAllocation> captor =
                org.mockito.ArgumentCaptor.forClass(PayoutAllocation.class);
        verify(allocationRepository).save(captor.capture());

        assertEquals("booking-4", captor.getValue().getBookingId());
        assertEquals("payment-4", captor.getValue().getPaymentId());
        assertEquals(0, new BigDecimal("5000.00")
                .compareTo(captor.getValue().getAmount()));
    }

    @Test
    void reserveRejectsInsufficientMixedBookingEligibleEarnings() {
        arrangeMixedBookingReservation(new BigDecimal("4000.00"));

        ApiException exception = assertThrows(
                ApiException.class, () -> service.reserve(payoutRequest()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ELIGIBLE_EARNINGS", exception.getErrorCode());
    }

    private void arrangeMixedBookingReservation(BigDecimal matureAvailable) {
        when(payoutRepository.findByRequestKey("payout-1"))
                .thenReturn(Optional.empty());
        when(payoutRepository.save(any(Payout.class))).thenAnswer(invocation -> {
            Payout saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", "payout-db-1");
            return saved;
        });

        when(ledgerRepository.findByProviderIdOrderByCreatedAtDesc("provider-1"))
                .thenReturn(List.of(
                        bookingLedgerEntry("booking-1", "payment-1"),
                        bookingLedgerEntry("booking-2", "payment-2"),
                        bookingLedgerEntry("booking-3", "payment-3"),
                        bookingLedgerEntry("booking-4", "payment-4")));

        for (int i = 1; i <= 4; i++) {
            String bookingId = "booking-" + i;
            String paymentId = "payment-" + i;

            PaymentStatus paymentStatus = i == 2
                    ? PaymentStatus.REFUNDED : PaymentStatus.CAPTURED;

            when(snapshotService.getSnapshot(bookingId)).thenReturn(
                    new BookingFinancialSnapshotService.FinancialSnapshot(
                            paymentId, bookingId, "customer-1", "provider-1",
                            "LKR", paymentStatus,
                            new BigDecimal("9000.00"),
                            new BigDecimal("1000.00"),
                            new BigDecimal("8000.00"),
                            i == 2 ? new BigDecimal("1000.00") : BigDecimal.ZERO));

            when(bookingFacade.getBookingParticipants(bookingId)).thenReturn(
                    new BookingParticipants(
                            bookingId, null, "listing-1", "customer-1",
                            "provider-1", BookingStatus.COMPLETED,
                            NOW.minus(Duration.ofDays(10)),
                            NOW.minus(Duration.ofDays(9))));

            when(completionLookup.findConfirmedAt(bookingId)).thenReturn(
                    Optional.of(i == 1
                            ? NOW.minus(Duration.ofHours(71))
                            : NOW.minus(Duration.ofHours(73))));

            when(holdRepository.findByBookingId(bookingId))
                    .thenReturn(List.of());
        }

        DisputePayoutHold hold = new DisputePayoutHold(
                "dispute-3", "booking-3", "payment-3", "provider-1",
                new BigDecimal("5000.00"), "LKR");
        hold.markApplied();

        when(holdRepository.findByBookingId("booking-3"))
                .thenReturn(List.of(hold));

        when(allocationCalculator.calculateAvailable(any()))
                .thenReturn(matureAvailable);
    }

    private EarningsLedgerEntry bookingLedgerEntry(
            String bookingId, String paymentId) {
        EarningsLedgerEntry entry = new EarningsLedgerEntry();
        entry.setEventKey("earning-pending:" + paymentId);
        entry.setBookingId(bookingId);
        entry.setPaymentId(paymentId);
        entry.setProviderId("provider-1");
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setAmount(new BigDecimal("8000.00"));
        entry.setCurrency("LKR");
        entry.setSequenceNumber(1L);
        entry.setCreatedAt(NOW.minus(Duration.ofDays(5)));
        return entry;
    }
    @Test
    void reserveRejectsBookingOwnershipMismatch() {
        Payout payout = arrangeSuccessfulReservationInputs(
                NOW.minus(Duration.ofHours(73)));

        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(
                new BookingParticipants(
                        "booking-1", null, "listing-1", "customer-1",
                        "different-provider", BookingStatus.COMPLETED,
                        NOW.minus(Duration.ofDays(5)),
                        NOW.minus(Duration.ofDays(4))));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.reserve(payout));

        assertEquals(
                "Payout allocation booking ownership mismatch",
                exception.getMessage());
    }
    @Test
    void reserveRetryRejectsMissingAllocations() {
        arrangeExistingPendingPayout();

        when(allocationRepository.findByPayoutId("payout-db-1"))
                .thenReturn(List.of());

        assertIncompleteReservation();
    }

    @Test
    void reserveRetryRejectsMissingReserveLedgerEvidence() {
        PayoutAllocation allocation = arrangeExistingPendingAllocation(
                new BigDecimal("5000.00"));

        when(ledgerRepository.findByEventKey(reserveKey(allocation)))
                .thenReturn(Optional.empty());

        assertIncompleteReservation();
    }

    @Test
    void reserveRetryRejectsMismatchedReserveLedgerEvidence() {
        PayoutAllocation allocation = arrangeExistingPendingAllocation(
                new BigDecimal("5000.00"));

        EarningsLedgerEntry evidence = reserveEvidence(
                allocation, new BigDecimal("4000.00"));

        when(ledgerRepository.findByEventKey(reserveKey(allocation)))
                .thenReturn(Optional.of(evidence));

        assertIncompleteReservation();
    }

    @Test
    void reserveRetryRejectsAllocationTotalMismatch() {
        PayoutAllocation allocation = arrangeExistingPendingAllocation(
                new BigDecimal("4000.00"));

        when(ledgerRepository.findByEventKey(reserveKey(allocation)))
                .thenReturn(Optional.of(reserveEvidence(
                        allocation, new BigDecimal("4000.00"))));

        assertIncompleteReservation();
    }

    @Test
    void reserveRetryReturnsExistingPayoutWithVerifiedEvidence() {
        Payout existing = arrangeExistingPendingPayout();

        PayoutAllocation allocation = new PayoutAllocation(
                "payout-db-1", "provider-1", "payment-1",
                "booking-1", new BigDecimal("5000.00"), "LKR");

        when(allocationRepository.findByPayoutId("payout-db-1"))
                .thenReturn(List.of(allocation));

        when(ledgerRepository.findByEventKey(reserveKey(allocation)))
                .thenReturn(Optional.of(reserveEvidence(
                        allocation, new BigDecimal("5000.00"))));

        Payout result = service.reserve(payoutRequest());

        org.junit.jupiter.api.Assertions.assertSame(existing, result);
        org.mockito.Mockito.verify(payoutRepository,
                org.mockito.Mockito.never()).save(any(Payout.class));
    }

    private Payout arrangeExistingPendingPayout() {
        Payout existing = payoutRequest();
        ReflectionTestUtils.setField(existing, "id", "payout-db-1");

        when(payoutRepository.findByRequestKey("payout-1"))
                .thenReturn(Optional.of(existing));

        return existing;
    }

    private PayoutAllocation arrangeExistingPendingAllocation(
            BigDecimal amount) {
        arrangeExistingPendingPayout();

        PayoutAllocation allocation = new PayoutAllocation(
                "payout-db-1", "provider-1", "payment-1",
                "booking-1", amount, "LKR");

        when(allocationRepository.findByPayoutId("payout-db-1"))
                .thenReturn(List.of(allocation));

        return allocation;
    }

    private String reserveKey(PayoutAllocation allocation) {
        return "PAYOUT_RESERVE:payout-db-1:"
                + allocation.getPaymentId() + ":"
                + allocation.getBookingId();
    }

    private EarningsLedgerEntry reserveEvidence(
            PayoutAllocation allocation, BigDecimal amount) {
        EarningsLedgerEntry evidence = new EarningsLedgerEntry();
        evidence.setEventKey(reserveKey(allocation));
        evidence.setEventType(EarningsLedgerEventType.PAYOUT_RESERVE);
        evidence.setProviderId("provider-1");
        evidence.setPaymentId(allocation.getPaymentId());
        evidence.setBookingId(allocation.getBookingId());
        evidence.setCurrency("LKR");
        evidence.setAmount(amount);
        evidence.setSequenceNumber(1L);
        evidence.setCreatedAt(NOW.minus(Duration.ofHours(1)));
        return evidence;
    }

    private void assertIncompleteReservation() {
        ApiException exception = assertThrows(
                ApiException.class, () -> service.reserve(payoutRequest()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INCOMPLETE_PAYOUT_RESERVATION",
                exception.getErrorCode());
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

    private Payout arrangeSuccessfulReservationInputs(Instant confirmedAt) {
        Payout payout = arrangeBaseReservationMocks();
        when(completionLookup.findConfirmedAt("booking-1"))
                .thenReturn(Optional.of(confirmedAt));
        when(allocationCalculator.calculateAvailable(any()))
                .thenReturn(new BigDecimal("5000.00"));
        return payout;
    }

    private Payout arrangeBaseReservationMocks() {
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
        when(holdRepository.findByBookingId("booking-1")).thenReturn(List.of());
        return payout;
    }
}
