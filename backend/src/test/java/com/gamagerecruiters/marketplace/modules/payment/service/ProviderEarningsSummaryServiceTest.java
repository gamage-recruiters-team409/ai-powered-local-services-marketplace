
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsSummaryResponse;

/**
 * Unit tests for provider earnings summary retrieval.
 *
 * <p>
 * Verifies provider-scoped ledger access, currency filtering,
 * balance calculation and invalid input handling.
 * </p>
 */
class ProviderEarningsSummaryServiceTest {

    private EarningsLedgerService ledgerService;
    private ProviderEarningsSummaryService summaryService;

    @BeforeEach
    void setUp() {
        ledgerService = mock(EarningsLedgerService.class);

        summaryService = new ProviderEarningsSummaryService(
                ledgerService,
                new EarningsBalanceCalculator());
    }

    @Test
    void returnsCalculatedProviderEarningsSummary() {
        when(ledgerService.getProviderHistory("provider-001"))
                .thenReturn(List.of(
                        event("e1", "LKR",
                                EarningsLedgerEventType.EARNING_PENDING, "9000"),
                        event("e2", "LKR",
                                EarningsLedgerEventType.EARNING_AVAILABLE, "9000"),
                        event("e3", "LKR",
                                EarningsLedgerEventType.PAYOUT_DEBIT, "5000")));

        EarningsSummaryResponse response = summaryService.getSummary("provider-001", "LKR");

        assertEquals("LKR", response.currency());
        assertAmount("0", response.pending());
        assertAmount("4000", response.available());
        assertAmount("0", response.held());
        assertAmount("5000", response.paidOut());

        verify(ledgerService).getProviderHistory("provider-001");
    }

    @Test
    void returnsZeroBalancesWhenProviderHasNoLedgerEntries() {
        when(ledgerService.getProviderHistory("provider-001"))
                .thenReturn(List.of());

        EarningsSummaryResponse response = summaryService.getSummary("provider-001", "LKR");

        assertAmount("0", response.pending());
        assertAmount("0", response.available());
        assertAmount("0", response.held());
        assertAmount("0", response.paidOut());
    }

    @Test
    void excludesOtherCurrenciesFromLkrSummary() {
        when(ledgerService.getProviderHistory("provider-001"))
                .thenReturn(List.of(
                        event("e1", "LKR",
                                EarningsLedgerEventType.EARNING_PENDING, "2000"),
                        event("e2", "USD",
                                EarningsLedgerEventType.EARNING_PENDING, "100")));

        EarningsSummaryResponse response = summaryService.getSummary("provider-001", "LKR");

        assertAmount("2000", response.pending());
        assertAmount("0", response.available());
    }

    @Test
    void rejectsBlankProviderId() {
        assertThrows(IllegalArgumentException.class,
                () -> summaryService.getSummary(" ", "LKR"));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void rejectsNullProviderId() {
        assertThrows(IllegalArgumentException.class,
                () -> summaryService.getSummary(null, "LKR"));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> summaryService.getSummary("provider-001", "USD"));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void rejectsNullCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> summaryService.getSummary("provider-001", null));

        verifyNoInteractions(ledgerService);
    }

    /**
     * Creates a valid ledger event for the summary tests.
     */
    private EarningsLedgerEntry event(
            String eventKey,
            String currency,
            EarningsLedgerEventType type,
            String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(eventKey);
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId("provider-001");
        entry.setEventType(type);
        entry.setAmount(new BigDecimal(amount));
        entry.setCurrency(currency);

        int sequence = Integer.parseInt(eventKey.substring(1));
        entry.setSequenceNumber((long) sequence);

        entry.setCreatedAt(
                Instant.parse("2026-11-16T10:00:00Z")
                        .plusSeconds(sequence));

        return entry;
    }

    /**
     * Compares monetary values independently of BigDecimal scale.
     */
    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
