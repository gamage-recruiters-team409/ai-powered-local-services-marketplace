package com.gamagerecruiters.marketplace.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class DisputePayoutHoldTest {

    /**
     * Trailing zeros must not invalidate an amount with two-decimal precision.
     */
    @Test
    void constructor_acceptsTrailingZeros() {
        DisputePayoutHold hold = new DisputePayoutHold(
                "dispute-1",
                "booking-1",
                "payment-1",
                "provider-1",
                new BigDecimal("90.000"),
                "LKR");

        assertEquals(0, hold.getAmount().compareTo(new BigDecimal("90.00")));
    }

    /**
     * Amounts requiring more than two decimal places must be rejected.
     */
    @Test
    void constructor_rejectsExcessDecimalPrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisputePayoutHold(
                        "dispute-1",
                        "booking-1",
                        "payment-1",
                        "provider-1",
                        new BigDecimal("90.555"),
                        "LKR"));
    }

    /**
     * A financial hold cannot have a zero or negative amount.
     */
    @Test
    void constructor_rejectsNonPositiveAmounts() {
        for (String amount : new String[] {"0.00", "-10.00"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DisputePayoutHold(
                            "dispute-1",
                            "booking-1",
                            "payment-1",
                            "provider-1",
                            new BigDecimal(amount),
                            "LKR"));
        }
    }
}