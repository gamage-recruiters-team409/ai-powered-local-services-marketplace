
package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Represents a payment record stored in the MongoDB payments collection.
 *
 * Monetary values are explicitly mapped to BSON Decimal128
 * to preserve decimal precision during database persistence.
 */
@Document(collection = "payments")
public class Payment {

    @Id
    private String id;

    @Indexed
    private String bookingId;

    /**
     * Customer identifier captured from verified BookingFacade data
     * when the payment is created.
     *
     * <p>
     * This value must never be populated from untrusted client input.
     * It enables customer-scoped payment queries without repeatedly
     * retrieving booking ownership information.
     * </p>
     */
    private String customerId;

    private String gatewayRef;

    /**
     * Unique token identifying an active Stripe Checkout reservation.
     * Null when no reservation is active.
     */
    private String checkoutReservationToken;

    /**
     * Timestamp when the Checkout reservation was acquired.
     * Used for safe recovery and reconciliation.
     */
    private Instant checkoutReservedAt;

    // Store the payment amount as MongoDB Decimal128.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private String currency;

    private PaymentStatus status;

    // Store the refunded amount as MongoDB Decimal128.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal refundedAmount;

    // Store the commission amount as MongoDB Decimal128.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal commissionAmount;

    // Store the commission rate as MongoDB Decimal128.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal commissionRate;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * Receipt identifier assigned by a trusted payment workflow.
     * Null until a receipt is actually issued.
     */
    private String receiptReference;

    /**
     * Refund identifier assigned by a trusted refund workflow.
     */
    private String refundReference;

    /**
     * Customer-visible refund processing state.
     * Null when no verified refund state is available.
     */
    private String refundStatus;

    /**
     * Verified payment capture timestamp.
     * Must not be inferred from creation or update timestamps.
     */
    private Instant paidAt;

    public Payment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }


    /**
     * Returns the customer identifier captured at payment creation.
     *
     * @return verified customer identifier, or null for legacy records
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Sets the verified customer identifier for this payment.
     *
     * <p>
     * Only trusted internal payment creation workflows should call
     * this method using customer information from BookingFacade.
     * </p>
     *
     * @param customerId verified customer identifier
     */
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getGatewayRef() {
        return gatewayRef;
    }

    public void setGatewayRef(String gatewayRef) {
        this.gatewayRef = gatewayRef;
    }

    public String getCheckoutReservationToken() {
        return checkoutReservationToken;
    }

    public void setCheckoutReservationToken(String checkoutReservationToken) {
        this.checkoutReservationToken = checkoutReservationToken;
    }

    public Instant getCheckoutReservedAt() {
        return checkoutReservedAt;
    }

    public void setCheckoutReservedAt(Instant checkoutReservedAt) {
        this.checkoutReservedAt = checkoutReservedAt;
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

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public void setCommissionAmount(BigDecimal commissionAmount) {
        this.commissionAmount = commissionAmount;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getReceiptReference() {
        return receiptReference;
    }

    public void setReceiptReference(String receiptReference) {
        this.receiptReference = receiptReference;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public void setRefundReference(String refundReference) {
        this.refundReference = refundReference;
    }

    public String getRefundStatus() {
        return refundStatus;
    }

    public void setRefundStatus(String refundStatus) {
        this.refundStatus = refundStatus;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }
}
