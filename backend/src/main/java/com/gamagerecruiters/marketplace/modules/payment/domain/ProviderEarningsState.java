
package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;

import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Stores the current financial state for one provider and currency.
 *
 * <p>
 * This document is updated atomically with the append-only earnings
 * ledger inside a MongoDB transaction.
 * </p>
 *
 * <p>
 * The ledger remains the financial audit record. This state provides
 * a concurrency-controlled balance snapshot and processing sequence.
 * </p>
 */
@Document(collection = "provider_earnings_state")
public class ProviderEarningsState {

    @MongoId(FieldType.STRING)
    private String id;

    private String providerId;

    private String currency;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal pending = BigDecimal.ZERO;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal available = BigDecimal.ZERO;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal held = BigDecimal.ZERO;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal paidOut = BigDecimal.ZERO;

    private long lastSequenceNumber = 0L;

    public ProviderEarningsState() {
    }

    /**
     * Creates an initial zero-balance state for a provider and currency.
     *
     * A deterministic document ID ensures that concurrent requests
     * target the same financial state record.
     */
    public ProviderEarningsState(String providerId, String currency) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }

        this.providerId = providerId;
        this.currency = currency;
        this.id = providerId.length() + ":" + providerId + ":" + currency;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getPending() {
        return pending;
    }

    public void setPending(BigDecimal pending) {
        this.pending = pending;
    }

    public BigDecimal getAvailable() {
        return available;
    }

    public void setAvailable(BigDecimal available) {
        this.available = available;
    }

    public BigDecimal getHeld() {
        return held;
    }

    public void setHeld(BigDecimal held) {
        this.held = held;
    }

    public BigDecimal getPaidOut() {
        return paidOut;
    }

    public void setPaidOut(BigDecimal paidOut) {
        this.paidOut = paidOut;
    }

    /**
     * Applies a financial event to this provider's balance snapshot.
     *
     * <p>
     * This method only changes the in-memory state. The caller must
     * persist the state and append the ledger event atomically within
     * the same MongoDB transaction.
     * </p>
     *
     * @param entry validated financial event for this provider/currency
     * @throws IllegalArgumentException if the event belongs to another
     *                                  provider or currency
     * @throws IllegalStateException    if the event would create a
     *                                  negative financial balance
     */
    public void applyEvent(EarningsLedgerEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Ledger entry is required");
        }

        entry.validate();

        if (!providerId.equals(entry.getProviderId())
                || !currency.equals(entry.getCurrency())) {
            throw new IllegalArgumentException(
                    "Ledger event does not match provider earnings state");
        }

        BigDecimal amount = entry.getAmount();

        BigDecimal nextPending = pending;
        BigDecimal nextAvailable = available;
        BigDecimal nextHeld = held;
        BigDecimal nextPaidOut = paidOut;

        switch (entry.getEventType()) {
            case EARNING_PENDING ->
                nextPending = nextPending.add(amount);

            case EARNING_AVAILABLE -> {
                nextPending = nextPending.subtract(amount);
                nextAvailable = nextAvailable.add(amount);
            }

            case EARNING_HOLD -> {
                nextAvailable = nextAvailable.subtract(amount);
                nextHeld = nextHeld.add(amount);
            }

            case EARNING_RELEASE -> {
                nextHeld = nextHeld.subtract(amount);
                nextAvailable = nextAvailable.add(amount);
            }

            case PAYOUT_DEBIT -> {
                nextAvailable = nextAvailable.subtract(amount);
                nextPaidOut = nextPaidOut.add(amount);
            }

            case REFUND_FROM_PENDING ->
                nextPending = nextPending.subtract(amount);

            case REFUND_FROM_AVAILABLE ->
                nextAvailable = nextAvailable.subtract(amount);

            case REFUND_FROM_HELD ->
                nextHeld = nextHeld.subtract(amount);
        }

        // Reject invalid financial transitions before changing state.
        if (nextPending.signum() < 0
                || nextAvailable.signum() < 0
                || nextHeld.signum() < 0) {
            throw new IllegalStateException(
                    "Ledger event would create a negative earnings balance");
        }

        pending = nextPending;
        available = nextAvailable;
        held = nextHeld;
        paidOut = nextPaidOut;
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public void setLastSequenceNumber(long lastSequenceNumber) {
        this.lastSequenceNumber = lastSequenceNumber;
    }
}
