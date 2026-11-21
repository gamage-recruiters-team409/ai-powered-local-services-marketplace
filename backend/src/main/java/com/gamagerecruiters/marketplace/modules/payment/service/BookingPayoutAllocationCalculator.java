package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;

/**
 * Calculates payout-eligible earnings for a single booking/payment pair.
 *
 * <p>
 * Reuses the existing earnings balance calculator to preserve
 * financial event replay semantics, including dispute holds,
 * refunds, and previously reserved or settled payouts.
 * </p>
 *
 * <p>
 * This calculator does not reserve funds or persist allocations.
 * The payout transaction service must enforce concurrency safety
 * and atomic financial updates.
 * </p>
 */
@Component
public class BookingPayoutAllocationCalculator {

    private final EarningsBalanceCalculator balanceCalculator;

    public BookingPayoutAllocationCalculator(
            EarningsBalanceCalculator balanceCalculator) {
        this.balanceCalculator = Objects.requireNonNull(
                balanceCalculator, "Balance calculator is required");
    }

    /**
     * Calculates the remaining available earnings for one booking/payment.
     *
     * @param entries ledger entries for the booking/payment pair
     * @return available earnings, or zero for empty history
     */
    public BigDecimal calculateAvailable(
            List<EarningsLedgerEntry> entries) {

        Objects.requireNonNull(entries, "Ledger entries are required");

        if (entries.isEmpty()) {
            return BigDecimal.ZERO;
        }

        String bookingId = null;
        String paymentId = null;

        for (EarningsLedgerEntry entry : entries) {
            Objects.requireNonNull(entry, "Ledger entry must not be null");

            if (bookingId == null) {
                bookingId = requireText(entry.getBookingId(), "Booking ID");
                paymentId = requireText(entry.getPaymentId(), "Payment ID");
            }

            if (!bookingId.equals(entry.getBookingId())
                    || !paymentId.equals(entry.getPaymentId())) {
                throw new IllegalArgumentException(
                        "Ledger entries must belong to the same booking/payment pair");
            }
        }

        // Existing calculator validates and replays financial events
        // using their sequence numbers.
        EarningsBalanceCalculator.EarningsBalance balance =
                balanceCalculator.calculate(entries);

        return balance.available();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }

        return value;
    }
}