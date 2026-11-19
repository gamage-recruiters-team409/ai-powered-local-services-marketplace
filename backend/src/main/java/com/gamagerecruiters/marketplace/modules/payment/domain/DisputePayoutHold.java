
package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Records a booking-specific payout hold requested by the Dispute module.
 *
 * <p>
 * A hold is identified by its dispute ID to support idempotent processing.
 * This record tracks the hold independently from the provider's aggregate
 * earnings balance.
 * </p>
 *
 * <p>
 * Creating this record does not itself transfer or reserve funds.
 * The financial hold must be confirmed by the payment workflow before
 * the record can be marked as applied.
 * </p>
 */
@Document(collection = "dispute_payout_holds")
public class DisputePayoutHold {

    @Id
    private String id;

    @Indexed(unique = true)
    private String disputeId;

    @Indexed
    private String bookingId;

    private String paymentId;
    private String providerId;
    private BigDecimal amount;
    private String currency;
    private HoldStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Lifecycle of a dispute-related payout hold.
     */
    public enum HoldStatus {
        PENDING,
        APPLIED,
        RELEASED,
        FAILED
    }

    protected DisputePayoutHold() {
        // Required by Spring Data MongoDB.
    }

    /**
     * Creates a pending hold without claiming that funds are reserved.
     */
    public DisputePayoutHold(
            String disputeId,
            String bookingId,
            String paymentId,
            String providerId,
            BigDecimal amount,
            String currency) {

        this.disputeId = requireText(disputeId, "Dispute ID");
        this.bookingId = requireText(bookingId, "Booking ID");
        this.paymentId = requireText(paymentId, "Payment ID");
        this.providerId = requireText(providerId, "Provider ID");
        this.currency = requireText(currency, "Currency");

        Objects.requireNonNull(amount, "Hold amount is required");

        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Hold amount must be positive with at most two decimal places");
        }

        this.amount = amount;
        this.status = HoldStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Marks the hold as applied only after financial reservation succeeds.
     */
    public void markApplied() {
        requireStatus(HoldStatus.PENDING);
        this.status = HoldStatus.APPLIED;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks a previously applied hold as released.
     */
    public void markReleased() {
        requireStatus(HoldStatus.APPLIED);
        this.status = HoldStatus.RELEASED;
        this.updatedAt = Instant.now();
    }

    /**
     * Records a failed attempt without claiming funds were reserved.
     */
    public void markFailed() {
        requireStatus(HoldStatus.PENDING);
        this.status = HoldStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    private void requireStatus(HoldStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Invalid payout hold transition from " + status);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }
        return value.trim();
    }

    public String getId() {
        return id;
    }

    public String getDisputeId() {
        return disputeId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getProviderId() {
        return providerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
