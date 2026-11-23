package com.gamagerecruiters.marketplace.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PayoutTest {

    @Test
    void constructor_acceptsLkrAtMinimum() {
        Payout payout = new Payout(
                "payout-request-1", "provider-1",
                new BigDecimal("5000.00"), "LKR");

        assertEquals(PayoutStatus.PENDING, payout.getStatus());
        assertEquals("LKR", payout.getCurrency());
        assertEquals(0, payout.getAmount().compareTo(new BigDecimal("5000.00")));
    }

    @Test
    void constructor_rejectsBelowMinimum() {
        assertThrows(IllegalArgumentException.class,
                () -> new Payout(
                        "payout-request-1", "provider-1",
                        new BigDecimal("4999.99"), "LKR"));
    }

    @Test
    void constructor_rejectsNonLkrCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> new Payout(
                        "payout-request-1", "provider-1",
                        new BigDecimal("5000.00"), "USD"));
    }

    @Test
    void constructor_rejectsExcessDecimalPrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> new Payout(
                        "payout-request-1", "provider-1",
                        new BigDecimal("5000.555"), "LKR"));
    }

    @Test
    void paidRequiresProcessingReferenceAndAdmin() {
        Payout payout = new Payout(
                "payout-request-1", "provider-1",
                new BigDecimal("5000.00"), "LKR");

        assertThrows(IllegalStateException.class,
                () -> payout.markPaid("admin-1", "BANK-REF-1"));

        payout.markProcessing("admin-1");
        assertThrows(IllegalArgumentException.class,
                () -> payout.markPaid("admin-1", " "));

        payout.markPaid("admin-1", "BANK-REF-1");
        assertEquals(PayoutStatus.PAID, payout.getStatus());
        assertEquals("BANK-REF-1", payout.getTransferReference());
    }

    @Test
    void lifecycleRejectsInvalidTransitions() {
        Payout payout = new Payout(
                "payout-request-1", "provider-1",
                new BigDecimal("5000.00"), "LKR");

        payout.markProcessing("admin-1");
        payout.markFailed("admin-1", "bank transfer rejected");

        assertEquals(PayoutStatus.FAILED, payout.getStatus());
        assertThrows(IllegalStateException.class,
                () -> payout.markPaid("admin-1", "BANK-REF-1"));
    }
}
