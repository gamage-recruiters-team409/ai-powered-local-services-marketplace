
package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Represents one financial event in the provider earnings ledger.
 *
 * Ledger entries are intended to be append-only.
 * Financial amounts are stored as MongoDB Decimal128.
 *
 * The service and persistence layers must enforce validation,
 * idempotency and append-only behavior.
 */
@Document(collection = "earnings_ledger")
public class EarningsLedgerEntry {

    @Id
    private String id;

    private String eventKey;

    private String paymentId;

    private String bookingId;

    private String providerId;

    private EarningsLedgerEventType eventType;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private String currency;

    private Instant createdAt;

    public EarningsLedgerEntry() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public EarningsLedgerEventType getEventType() {
        return eventType;
    }

    public void setEventType(EarningsLedgerEventType eventType) {
        this.eventType = eventType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Validates the required fields of this earnings ledger entry.
     *
     * This method checks the structural integrity of a financial
     * event before it is submitted for persistence.
     *
     * It does not verify payment authorization, booking ownership,
     * event idempotency, or append-only database behavior.
     *
     * @throws IllegalArgumentException if any required field is
     *                                  missing or the monetary amount is invalid
     */
    public void validate() {

        // Every financial event requires a non-blank idempotency key.
        if (eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Event key must not be blank");
        }

        // Link the ledger entry to its originating payment.
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "Payment ID must not be blank");
        }

        // Every earnings event must belong to a booking.
        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException(
                    "Booking ID must not be blank");
        }

        // Provider ownership is required for earnings tracking.
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider ID must not be blank");
        }

        // The event type identifies the financial operation.
        if (eventType == null) {
            throw new IllegalArgumentException(
                    "Earnings ledger event type is required");
        }

        // Financial events must contain a valid non-negative amount.
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(
                    "Ledger amount must not be null or negative");
        }

        // This initial model supports monetary values up to two
        // decimal places, consistent with the current payment model.
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Ledger amount must not exceed two decimal places");
        }

        // Currency is required to interpret the monetary amount.
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "Currency must not be blank");
        }

        // A timestamp is required for financial event traceability.
        if (createdAt == null) {
            throw new IllegalArgumentException(
                    "Ledger creation timestamp is required");
        }
    }

}
