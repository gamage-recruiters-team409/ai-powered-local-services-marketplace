
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsBalanceCalculator.EarningsBalance;

/**
 * Unit tests for provider earnings balance calculations.
 *
 * Verifies financial transfers, refunds, payout deductions,
 * validation and isolation between providers and currencies.
 */
class EarningsBalanceCalculatorTest {

    private EarningsBalanceCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new EarningsBalanceCalculator();
    }

    @Test
    void emptyLedgerReturnsZeroBalances() {
        EarningsBalance balance = calculator.calculate(List.of());

        assertBalance(balance, "0", "0", "0", "0");
    }

    @Test
    void pendingEventIncreasesPendingBalance() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000")));

        assertBalance(balance, "9000", "0", "0", "0");
    }

    @Test
    void availableEventTransfersPendingToAvailable() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "9000")));

        assertBalance(balance, "0", "9000", "0", "0");
    }

    @Test
    void holdEventTransfersAvailableToHeld() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "9000"),
                event("e3", EarningsLedgerEventType.EARNING_HOLD, "2000")));

        assertBalance(balance, "0", "7000", "2000", "0");
    }

    @Test
    void releaseEventTransfersHeldToAvailable() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "9000"),
                event("e3", EarningsLedgerEventType.EARNING_HOLD, "2000"),
                event("e4", EarningsLedgerEventType.EARNING_RELEASE, "2000")));

        assertBalance(balance, "0", "9000", "0", "0");
    }

    @Test
    void payoutDebitReducesAvailableAndIncreasesPaidOut() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "9000"),
                event("e3", EarningsLedgerEventType.PAYOUT_DEBIT, "5000")));

        assertBalance(balance, "0", "4000", "0", "5000");
    }

    @Test
    void refundFromPendingReducesPendingBalance() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.REFUND_FROM_PENDING, "2000")));

        assertBalance(balance, "7000", "0", "0", "0");
    }

    @Test
    void refundFromAvailableReducesAvailableBalance() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "9000"),
                event("e3", EarningsLedgerEventType.REFUND_FROM_AVAILABLE, "2000")));

        assertBalance(balance, "0", "7000", "0", "0");
    }

    @Test
    void refundFromHeldReducesHeldBalance() {
        EarningsBalance balance = calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "9000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "9000"),
                event("e3", EarningsLedgerEventType.EARNING_HOLD, "3000"),
                event("e4", EarningsLedgerEventType.REFUND_FROM_HELD, "1000")));

        assertBalance(balance, "0", "6000", "2000", "0");
    }

    @Test
    void rejectsEventThatCreatesNegativeBalance() {
        assertThrows(IllegalStateException.class, () -> calculator.calculate(List.of(
                event("e1",
                        EarningsLedgerEventType.EARNING_AVAILABLE,
                        "1000"))));
    }

    @Test
    void rejectsPayoutExceedingAvailableBalance() {
        assertThrows(IllegalStateException.class, () -> calculator.calculate(List.of(
                event("e1", EarningsLedgerEventType.EARNING_PENDING, "1000"),
                event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "1000"),
                event("e3", EarningsLedgerEventType.PAYOUT_DEBIT, "2000"))));
    }

    @Test
    void rejectsMixedProviders() {
        EarningsLedgerEntry first = event("e1", EarningsLedgerEventType.EARNING_PENDING, "1000");

        EarningsLedgerEntry second = event("e2", EarningsLedgerEventType.EARNING_PENDING, "1000");

        second.setProviderId("another-provider");

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(List.of(first, second)));
    }

    @Test
    void rejectsMixedCurrencies() {
        EarningsLedgerEntry first = event("e1", EarningsLedgerEventType.EARNING_PENDING, "1000");

        EarningsLedgerEntry second = event("e2", EarningsLedgerEventType.EARNING_PENDING, "1000");

        second.setCurrency("USD");

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(List.of(first, second)));
    }

    @Test
    void rejectsNullLedgerList() {
        assertThrows(NullPointerException.class,
                () -> calculator.calculate(null));
    }

    @Test
    void rejectsInvalidLedgerEntry() {
        EarningsLedgerEntry entry = event("e1", EarningsLedgerEventType.EARNING_PENDING, "1000");

        entry.setPaymentId(null);

        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(List.of(entry)));
    }

    @Test
    void sortsEventsBeforeCalculatingBalances() {
        EarningsLedgerEntry pending = event("e1", EarningsLedgerEventType.EARNING_PENDING, "5000");

        EarningsLedgerEntry available = event("e2", EarningsLedgerEventType.EARNING_AVAILABLE, "5000");

        pending.setCreatedAt(Instant.parse("2026-11-16T10:00:00Z"));
        available.setCreatedAt(Instant.parse("2026-11-16T11:00:00Z"));

        // Supply the events in reverse order.
        EarningsBalance balance = calculator.calculate(List.of(available, pending));

        assertBalance(balance, "0", "5000", "0", "0");
    }

    /**
     * Rejects ledger events with identical timestamps because their
     * financial processing order cannot be established reliably.
     */
    @Test
    void rejectsEventsWithAmbiguousTimestampOrdering() {
        EarningsLedgerEntry pending = event(
                "e1",
                EarningsLedgerEventType.EARNING_PENDING,
                "5000");

        EarningsLedgerEntry available = event(
                "e2",
                EarningsLedgerEventType.EARNING_AVAILABLE,
                "5000");

        // Both events share the same timestamp.
        Instant sameTimestamp = Instant.parse("2026-11-16T10:00:00Z");

        pending.setCreatedAt(sameTimestamp);
        available.setCreatedAt(sameTimestamp);

        assertThrows(
                IllegalStateException.class,
                () -> calculator.calculate(List.of(pending, available)));
    }

    /**
     * Creates a valid ledger event for calculator tests.
     */
    private EarningsLedgerEntry event(
            String eventKey,
            EarningsLedgerEventType type,
            String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(eventKey);
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId("provider-001");
        entry.setEventType(type);
        entry.setAmount(new BigDecimal(amount));
        entry.setCurrency("LKR");

        // Different timestamps preserve the intended event order.
        int sequence = Integer.parseInt(eventKey.substring(1));
        entry.setCreatedAt(
                Instant.parse("2026-11-16T10:00:00Z")
                        .plusSeconds(sequence));

        return entry;
    }

    /**
     * Compares monetary values without depending on BigDecimal scale.
     */
    private void assertBalance(
            EarningsBalance actual,
            String pending,
            String available,
            String held,
            String paidOut) {

        assertEquals(0, actual.pending().compareTo(new BigDecimal(pending)));
        assertEquals(0, actual.available().compareTo(new BigDecimal(available)));
        assertEquals(0, actual.held().compareTo(new BigDecimal(held)));
        assertEquals(0, actual.paidOut().compareTo(new BigDecimal(paidOut)));
    }
}
