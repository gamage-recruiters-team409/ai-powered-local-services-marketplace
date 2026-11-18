
package com.gamagerecruiters.marketplace.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for validating provider earnings ledger entries.
 *
 * Ensures that financial records contain valid identifiers,
 * event information, monetary values and timestamps.
 */
class EarningsLedgerEntryTest {

    /**
     * Creates a valid ledger entry for reuse across validation tests.
     */
    private EarningsLedgerEntry validEntry() {
        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey("payment-123:pending");
        entry.setPaymentId("payment-123");
        entry.setBookingId("booking-123");
        entry.setProviderId("provider-123");
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setAmount(new BigDecimal("9000.00"));
        entry.setCurrency("LKR");
        entry.setCreatedAt(Instant.now());

        return entry;
    }

    /**
     * A complete ledger entry should pass validation.
     */
    @Test
    void shouldAcceptValidLedgerEntry() {
        EarningsLedgerEntry entry = validEntry();

        assertDoesNotThrow(entry::validate);
    }

    /**
     * An event key is required for idempotent event processing.
     */
    @Test
    void shouldRejectBlankEventKey() {
        EarningsLedgerEntry entry = validEntry();
        entry.setEventKey(" ");

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * Every ledger entry must reference an existing payment identifier.
     */
    @Test
    void shouldRejectMissingPaymentId() {
        EarningsLedgerEntry entry = validEntry();
        entry.setPaymentId(null);

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * A provider identifier is required for earnings ownership.
     */
    @Test
    void shouldRejectMissingProviderId() {
        EarningsLedgerEntry entry = validEntry();
        entry.setProviderId("");

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * Financial events must have an explicit event type.
     */
    @Test
    void shouldRejectMissingEventType() {
        EarningsLedgerEntry entry = validEntry();
        entry.setEventType(null);

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * Ledger entries must not contain negative financial amounts.
     */
    @Test
    void shouldRejectNegativeAmount() {
        EarningsLedgerEntry entry = validEntry();
        entry.setAmount(new BigDecimal("-100.00"));

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * Monetary values must not contain more than two decimal places.
     */
    @Test
    void shouldRejectExcessDecimalPlaces() {
        EarningsLedgerEntry entry = validEntry();
        entry.setAmount(new BigDecimal("100.123"));

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * A ledger entry must specify its transaction currency.
     */
    @Test
    void shouldRejectBlankCurrency() {
        EarningsLedgerEntry entry = validEntry();
        entry.setCurrency(" ");

        assertThrows(IllegalArgumentException.class, entry::validate);
    }

    /**
     * Each financial event requires a creation timestamp.
     */
    @Test
    void shouldRejectMissingCreatedAt() {
        EarningsLedgerEntry entry = validEntry();
        entry.setCreatedAt(null);

        assertThrows(IllegalArgumentException.class, entry::validate);
    }
}
