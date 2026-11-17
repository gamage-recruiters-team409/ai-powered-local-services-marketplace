
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;

/**
 * Calculates provider earnings balances from financial ledger events.
 *
 * <p>
 * Each event transfers or deducts a monetary amount according to
 * its type. Invalid event sequences that would create a negative
 * balance are rejected.
 * </p>
 *
 * <p>
 * This calculator performs no database writes and does not
 * authorize financial operations.
 * </p>
 */
@Component
public class EarningsBalanceCalculator {

    /**
     * Immutable snapshot of calculated provider earnings.
     */
    public record EarningsBalance(
            BigDecimal pending,
            BigDecimal available,
            BigDecimal held,
            BigDecimal paidOut) {
    }

    /**
     * Calculates balances from a single provider's ledger events.
     *
     * @param entries financial events belonging to one provider
     * @return calculated earnings balance snapshot
     */
    public EarningsBalance calculate(List<EarningsLedgerEntry> entries) {
        Objects.requireNonNull(entries, "Ledger entries are required");

        // Validate entries before ordering financial events.
List<EarningsLedgerEntry> ordered = entries.stream()
        .map(entry -> Objects.requireNonNull(
                entry, "Ledger entry must not be null"))
        .peek(EarningsLedgerEntry::validate)
        .sorted(Comparator.comparing(EarningsLedgerEntry::getCreatedAt))
        .toList();

// Reject timestamp collisions rather than guessing financial event order.
// A trusted sequence mechanism is required for a complete solution.
for (int i = 1; i < ordered.size(); i++) {
    Instant previousTimestamp = ordered.get(i - 1).getCreatedAt();
    Instant currentTimestamp = ordered.get(i).getCreatedAt();

    if (previousTimestamp.equals(currentTimestamp)) {
        throw new IllegalStateException(
                "Ambiguous earnings ledger event order: "
                        + "multiple events share timestamp "
                        + currentTimestamp);
    }
}

        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal held = BigDecimal.ZERO;
        BigDecimal paidOut = BigDecimal.ZERO;

        String providerId = null;
        String currency = null;

        for (EarningsLedgerEntry entry : ordered) {
            entry.validate();

            // Never combine events belonging to different providers.
            if (providerId == null) {
                providerId = entry.getProviderId();
            } else if (!providerId.equals(entry.getProviderId())) {
                throw new IllegalArgumentException(
                        "Ledger entries must belong to one provider");
            }

            // Prevent accidental aggregation across currencies.
            if (currency == null) {
                currency = entry.getCurrency();
            } else if (!currency.equals(entry.getCurrency())) {
                throw new IllegalArgumentException(
                        "Ledger entries must use the same currency");
            }

            BigDecimal amount = entry.getAmount();
            EarningsLedgerEventType type = entry.getEventType();

            switch (type) {
                case EARNING_PENDING -> pending = pending.add(amount);

                case EARNING_AVAILABLE -> {
                    pending = pending.subtract(amount);
                    available = available.add(amount);
                }

                case EARNING_HOLD -> {
                    available = available.subtract(amount);
                    held = held.add(amount);
                }

                case EARNING_RELEASE -> {
                    held = held.subtract(amount);
                    available = available.add(amount);
                }

                case PAYOUT_DEBIT -> {
                    available = available.subtract(amount);
                    paidOut = paidOut.add(amount);
                }

                case REFUND_FROM_PENDING ->
                    pending = pending.subtract(amount);

                case REFUND_FROM_AVAILABLE ->
                    available = available.subtract(amount);

                case REFUND_FROM_HELD ->
                    held = held.subtract(amount);
            }

            // Reject events that consume more than the available balance.
            if (pending.signum() < 0
                    || available.signum() < 0
                    || held.signum() < 0) {
                throw new IllegalStateException(
                        "Ledger event creates a negative earnings balance: "
                                + entry.getEventKey());
            }
        }

        return new EarningsBalance(
                pending,
                available,
                held,
                paidOut);
    }
}
