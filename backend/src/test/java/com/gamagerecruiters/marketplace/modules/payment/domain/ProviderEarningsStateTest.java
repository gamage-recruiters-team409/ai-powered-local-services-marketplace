package com.gamagerecruiters.marketplace.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for provider earnings state payout transitions.
 *
 * Verifies compatibility with legacy state documents that do not
 * contain the payoutReserved field.
 */
class ProviderEarningsStateTest {

    @Test
    void legacyStateWithoutReservedBalanceSupportsPayoutReservation() {
        ProviderEarningsState state = availableState("8000");

        // Simulate a legacy MongoDB document missing payoutReserved.
        state.setPayoutReserved(null);

        assertAmount("0", state.getPayoutReserved());

        state.applyEvent(event(
                EarningsLedgerEventType.PAYOUT_RESERVE, "3000"));

        assertAmount("5000", state.getAvailable());
        assertAmount("3000", state.getPayoutReserved());
    }

    @Test
    void failedPayoutReleasesReservedFunds() {
        ProviderEarningsState state = availableState("8000");

        state.applyEvent(event(
                EarningsLedgerEventType.PAYOUT_RESERVE, "3000"));

        state.applyEvent(event(
                EarningsLedgerEventType.PAYOUT_RELEASE, "3000"));

        assertAmount("8000", state.getAvailable());
        assertAmount("0", state.getPayoutReserved());
        assertAmount("0", state.getPaidOut());
    }

    @Test
    void successfulPayoutSettlesReservedFunds() {
        ProviderEarningsState state = availableState("8000");

        state.applyEvent(event(
                EarningsLedgerEventType.PAYOUT_RESERVE, "3000"));

        state.applyEvent(event(
                EarningsLedgerEventType.PAYOUT_SETTLE, "3000"));

        assertAmount("5000", state.getAvailable());
        assertAmount("0", state.getPayoutReserved());
        assertAmount("3000", state.getPaidOut());
    }

    @Test
    void rejectsReservationExceedingAvailableEarnings() {
        ProviderEarningsState state = availableState("2000");

        assertThrows(IllegalStateException.class,
                () -> state.applyEvent(event(
                        EarningsLedgerEventType.PAYOUT_RESERVE, "3000")));

        // A rejected event must not partially mutate financial balances.
        assertAmount("2000", state.getAvailable());
        assertAmount("0", state.getPayoutReserved());
    }

    @Test
    void rejectsSettlementExceedingReservedFunds() {
        ProviderEarningsState state = availableState("8000");

        state.applyEvent(event(
                EarningsLedgerEventType.PAYOUT_RESERVE, "2000"));

        assertThrows(IllegalStateException.class,
                () -> state.applyEvent(event(
                        EarningsLedgerEventType.PAYOUT_SETTLE, "3000")));

        assertAmount("2000", state.getPayoutReserved());
        assertAmount("0", state.getPaidOut());
    }

    private ProviderEarningsState availableState(String amount) {
        ProviderEarningsState state =
                new ProviderEarningsState("provider-001", "LKR");

        state.applyEvent(event(
                EarningsLedgerEventType.EARNING_PENDING, amount));

        state.applyEvent(event(
                EarningsLedgerEventType.EARNING_AVAILABLE, amount));

        return state;
    }

    private EarningsLedgerEntry event(
            EarningsLedgerEventType type,
            String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey("state-test-" + type.name());
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId("provider-001");
        entry.setCurrency("LKR");
        entry.setEventType(type);
        entry.setAmount(new BigDecimal(amount));
        entry.setCreatedAt(Instant.parse("2026-11-19T08:00:00Z"));

        return entry;
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(
                0,
                actual.compareTo(new BigDecimal(expected)));
    }
}