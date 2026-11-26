
package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Records one customer refund operation for a captured payment.
 *
 * <p>
 * Each operation has a stable idempotency key so repeated requests
 * can be associated with the same financial action. A refund operation
 * does not itself prove that money has been returned to the customer.
 * </p>
 *
 * <p>
 * Gateway execution, reconciliation and earnings adjustments are
 * handled by separate services.
 * </p>
 */
@Document(collection = "payment_refund_operations")
public class RefundOperation {

    /**
     * Tracks the lifecycle of an individual refund operation.
     */
    public enum RefundStatus {
        PENDING,
        PROCESSING,
        SUCCEEDED,
        FAILED,
        REQUIRES_RECONCILIATION
    }

    @Id
    private String id;

    private String idempotencyKey;

    private String paymentId;

    private String bookingId;

    private String disputeId;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private String currency;

    private RefundStatus status;

    /**
     * Financial allocation reserved before gateway refund execution.
     * Persisted to support safe retries, finalization and reconciliation.
     */
    private RefundReservation reservation;

    /**
     * Stripe PaymentIntent or equivalent gateway payment reference.
     */
    private String gatewayPaymentReference;

    /**
     * Gateway-generated reference identifying the refund.
     */
    private String gatewayRefundReference;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    @Version
    private Long version;

    /**
     * Required by Spring Data MongoDB.
     */
    public RefundOperation() {
    }

    /**
     * Creates a pending refund operation without contacting the gateway.
     *
     * @param idempotencyKey stable business operation key
     * @param paymentId      original captured payment identifier
     * @param bookingId      associated booking identifier
     * @param disputeId      associated dispute identifier, if applicable
     * @param amount         requested refund amount
     * @param currency       payment currency
     */
    public RefundOperation(
            String idempotencyKey,
            String paymentId,
            String bookingId,
            String disputeId,
            BigDecimal amount,
            String currency) {

        this.idempotencyKey = requireText(
                idempotencyKey, "Idempotency key");
        this.paymentId = requireText(paymentId, "Payment ID");
        this.bookingId = requireText(bookingId, "Booking ID");

        this.disputeId = disputeId == null || disputeId.isBlank()
                ? null
                : disputeId.trim();

        Objects.requireNonNull(amount, "Refund amount is required");

        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException(
                    "Refund amount must be positive with at most two decimal places");
        }

        this.amount = amount.setScale(2);
        this.currency = requireText(currency, "Currency")
                .toUpperCase(java.util.Locale.ROOT);

        this.status = RefundStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public String getDisputeId() {
        return disputeId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    /**
     * Returns the persisted financial reservation.
     *
     * @return reservation, or null if not yet reserved
     */
    public RefundReservation getReservation() {
        return reservation;
    }

    /**
     * Associates an immutable financial reservation with this operation.
     * The transaction service must enforce atomic reservation and persistence.
     *
     * @param reservation validated financial reservation
     */
    public void assignReservation(RefundReservation reservation) {

        Objects.requireNonNull(
                reservation, "Refund reservation is required");

        // Allow an identical retry without modifying the operation.
        if (this.reservation != null) {

            if (!this.reservation.equals(reservation)) {
                throw new IllegalStateException(
                        "Existing refund reservation cannot be replaced");
            }

            return;
        }

        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending refunds can receive a new reservation");
        }

        this.reservation = reservation;
        this.updatedAt = Instant.now();
    }

    public String getGatewayPaymentReference() {
        return gatewayPaymentReference;
    }

    public String getGatewayRefundReference() {
        return gatewayRefundReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Updates processing status locally.
     *
     * <p>
     * This setter does not authorize a state transition. Services
     * must enforce valid transitions and use conditional persistence
     * to prevent concurrent or duplicate refund execution.
     * </p>
     *
     * @param status the new refund status
     */
    public void setStatus(RefundStatus status) {
        this.status = Objects.requireNonNull(
                status, "Refund status is required");

        this.updatedAt = Instant.now();

        if (status == RefundStatus.SUCCEEDED) {
            this.completedAt = this.updatedAt;
        }
    }

    /**
     * Associates a verified gateway payment reference.
     *
     * @param reference gateway payment identifier
     */
    public void setGatewayPaymentReference(String reference) {
        this.gatewayPaymentReference = requireText(
                reference, "Gateway payment reference");
        this.updatedAt = Instant.now();
    }

    /**
     * Associates a gateway refund identifier.
     *
     * @param reference gateway refund identifier
     */
    public void setGatewayRefundReference(String reference) {
        this.gatewayRefundReference = requireText(
                reference, "Gateway refund reference");
        this.updatedAt = Instant.now();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }

        return value.trim();
    }
}
