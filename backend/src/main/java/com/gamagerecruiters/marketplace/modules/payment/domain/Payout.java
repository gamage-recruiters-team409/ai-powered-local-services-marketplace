package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Represents an admin-controlled manual payout request.
 *
 * <p>
 * Bank transfers are performed outside the application.
 * The payout is marked PAID only after an authorized admin
 * confirms the transfer and records its reference.
 * </p>
 *
 * <p>
 * Financial balance changes must be performed atomically
 * with the corresponding earnings ledger events.
 * </p>
 */
@Document(collection = "provider_payouts")
public class Payout {

    public static final String SUPPORTED_CURRENCY = "LKR";
    public static final BigDecimal MINIMUM_AMOUNT = new BigDecimal("5000.00");

    @Id
    private String id;

    @Indexed(unique = true)
    private String requestKey;

    @Indexed
    private String providerId;

    private String currency;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private PayoutStatus status;

    private String processedBy;
    private String transferReference;
    private String failureReason;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;

    @Version
    private Long version;

    protected Payout() {
        // Required by Spring Data MongoDB.
    }

    /**
     * Creates a new payout request without claiming that
     * funds have already been reserved or transferred.
     */
    public Payout(
            String requestKey,
            String providerId,
            BigDecimal amount,
            String currency) {

        this.requestKey = requireText(requestKey, "Request key");
        this.providerId = requireText(providerId, "Provider ID");

        String normalizedCurrency = requireText(currency, "Currency");
        if (!SUPPORTED_CURRENCY.equals(normalizedCurrency)) {
            throw new IllegalArgumentException("Payout currency must be LKR");
        }
        this.currency = SUPPORTED_CURRENCY;

        if (amount == null
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Payout amount must be positive with at most two decimal places");
        }
        if (amount.compareTo(MINIMUM_AMOUNT) < 0) {
            throw new IllegalArgumentException(
                    "Payout amount must be at least LKR 5000.00");
        }

        this.amount = amount;
        this.status = PayoutStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Moves a pending request into admin processing.
     */
    public void markProcessing(String adminId) {
        requireStatus(PayoutStatus.PENDING);

        this.processedBy = requireText(adminId, "Admin ID");
        this.status = PayoutStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    /**
     * Confirms that the external bank transfer succeeded.
     */
    public void markPaid(String adminId, String reference) {
        requireStatus(PayoutStatus.PROCESSING);

        this.processedBy = requireText(adminId, "Admin ID");
        this.transferReference = requireText(
                reference, "Bank transfer reference");

        this.status = PayoutStatus.PAID;
        this.paidAt = Instant.now();
        this.updatedAt = this.paidAt;
    }

    /**
     * Records a failed payout for controlled financial recovery.
     */
    public void markFailed(String adminId, String reason) {
        requireStatus(PayoutStatus.PROCESSING);

        this.processedBy = requireText(adminId, "Admin ID");
        this.failureReason = requireText(reason, "Failure reason");

        this.status = PayoutStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    private void requireStatus(PayoutStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Invalid payout transition from " + status);
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

    public String getRequestKey() {
        return requestKey;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public String getProcessedBy() {
        return processedBy;
    }

    public String getTransferReference() {
        return transferReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
