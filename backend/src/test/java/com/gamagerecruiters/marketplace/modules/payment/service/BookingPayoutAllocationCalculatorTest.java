package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;

/**
 * Unit tests for booking/payment-level payout availability.
 *
 * Verifies payout reservations, settlement, refunds,
 * dispute holds, sequence ordering and ledger isolation.
 */
class BookingPayoutAllocationCalculatorTest {

    private BookingPayoutAllocationCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new BookingPayoutAllocationCalculator(
                new EarningsBalanceCalculator());
    }

    @Test
    void emptyHistoryReturnsZero() {
        assertAmount("0", calculator.calculateAvailable(List.of()));
    }

    @Test
    void pendingEarningsAreNotAvailableForPayout() {
        assertAmount("0", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"))));
    }

    @Test
    void completedEarningsBecomeAvailable() {
        assertAmount("8000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"))));
    }

    @Test
    void partialPayoutReservationReducesAvailableEarnings() {
        assertAmount("5000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.PAYOUT_RESERVE, "3000"))));
    }

    @Test
    void payoutSettlementDoesNotDeductAvailableTwice() {
        assertAmount("5000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.PAYOUT_RESERVE, "3000"),
                event(4, EarningsLedgerEventType.PAYOUT_SETTLE, "3000"))));
    }

    @Test
    void failedPayoutReleaseRestoresAvailableEarnings() {
        assertAmount("8000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.PAYOUT_RESERVE, "3000"),
                event(4, EarningsLedgerEventType.PAYOUT_RELEASE, "3000"))));
    }

    @Test
    void disputeHoldReducesAvailableEarnings() {
        assertAmount("6000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.EARNING_HOLD, "2000"))));
    }

    @Test
    void disputeReleaseRestoresAvailableEarnings() {
        assertAmount("8000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.EARNING_HOLD, "2000"),
                event(4, EarningsLedgerEventType.EARNING_RELEASE, "2000"))));
    }

    @Test
    void refundReducesAvailableEarnings() {
        assertAmount("6500", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.REFUND_FROM_AVAILABLE, "1500"))));
    }

    @Test
    void legacyPayoutDebitReducesAvailableEarnings() {
        assertAmount("5000", calculator.calculateAvailable(List.of(
                event(1, EarningsLedgerEventType.EARNING_PENDING, "8000"),
                event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "8000"),
                event(3, EarningsLedgerEventType.PAYOUT_DEBIT, "3000"))));
    }

    @Test
    void rejectsPayoutReservationExceedingAvailableBalance() {
        assertThrows(IllegalStateException.class,
                () -> calculator.calculateAvailable(List.of(
                        event(1, EarningsLedgerEventType.EARNING_PENDING, "2000"),
                        event(2, EarningsLedgerEventType.EARNING_AVAILABLE, "2000"),
                        event(3, EarningsLedgerEventType.PAYOUT_RESERVE, "3000"))));
    }

    @Test
    void rejectsMixedBookings() {
        EarningsLedgerEntry first =
                event(1, EarningsLedgerEventType.EARNING_PENDING, "1000");

        EarningsLedgerEntry second =
                event(2, EarningsLedgerEventType.EARNING_PENDING, "1000");

        second.setBookingId("another-booking");

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculateAvailable(List.of(first, second)));
    }

    @Test
    void rejectsMixedPayments() {
        EarningsLedgerEntry first =
                event(1, EarningsLedgerEventType.EARNING_PENDING, "1000");

        EarningsLedgerEntry second =
                event(2, EarningsLedgerEventType.EARNING_PENDING, "1000");

        second.setPaymentId("another-payment");

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculateAvailable(List.of(first, second)));
    }

    @Test
    void supportsProviderWideSequenceGaps() {
        // Other bookings may occupy intervening provider-wide sequences.
        EarningsLedgerEntry pending =
                event(2, EarningsLedgerEventType.EARNING_PENDING, "8000");

        EarningsLedgerEntry available =
                event(7, EarningsLedgerEventType.EARNING_AVAILABLE, "8000");

        assertAmount("8000", calculator.calculateAvailable(
                List.of(available, pending)));
    }

    @Test
    void rejectsDuplicateSequences() {
        EarningsLedgerEntry first =
                event(1, EarningsLedgerEventType.EARNING_PENDING, "1000");

        EarningsLedgerEntry second =
                event(1, EarningsLedgerEventType.EARNING_AVAILABLE, "1000");

        assertThrows(IllegalStateException.class,
                () -> calculator.calculateAvailable(List.of(first, second)));
    }

    @Test
    void rejectsNullHistory() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculateAvailable(null));
    }

    /**
     * Creates a valid persisted ledger event for one booking/payment.
     */
    private EarningsLedgerEntry event(
            long sequence,
            EarningsLedgerEventType type,
            String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey("booking-payout-test-" + sequence);
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId("provider-001");
        entry.setEventType(type);
        entry.setAmount(new BigDecimal(amount));
        entry.setCurrency("LKR");
        entry.setSequenceNumber(sequence);
        entry.setCreatedAt(
                Instant.parse("2026-11-16T10:00:00Z")
                        .plusSeconds(sequence));

        return entry;
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(
                0,
                actual.compareTo(new BigDecimal(expected)));
    }
}