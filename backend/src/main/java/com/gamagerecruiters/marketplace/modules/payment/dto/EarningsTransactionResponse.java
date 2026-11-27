package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;

/**
 * Provider-facing representation of a persisted earnings ledger event.
 *
 * <p>The amount represents the financial movement associated with the
 * event type, not necessarily newly earned income or a commission.</p>
 *
 * <p>Internal idempotency keys and provider identifiers are excluded
 * from the response.</p>
 */
public record EarningsTransactionResponse(
        String transactionId,
        String paymentId,
        String bookingId,
        EarningsLedgerEventType eventType,
        BigDecimal amount,
        String currency,
        Long sequenceNumber,
        Instant createdAt) {

    /**
     * Maps a persisted ledger entry to a provider-facing response.
     *
     * @param entry the provider-owned ledger entry
     * @return the transaction response
     */
    public static EarningsTransactionResponse from(
            EarningsLedgerEntry entry) {

        Objects.requireNonNull(entry, "Ledger entry is required");

        return new EarningsTransactionResponse(
                entry.getId(),
                entry.getPaymentId(),
                entry.getBookingId(),
                entry.getEventType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getSequenceNumber(),
                entry.getCreatedAt());
    }
}
