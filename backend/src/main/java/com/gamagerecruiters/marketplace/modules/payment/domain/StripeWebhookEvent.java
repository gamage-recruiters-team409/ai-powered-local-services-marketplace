package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stores the processing state of an authenticated Stripe webhook event.
 *
 * <p>The Stripe event ID is used as the MongoDB document ID to prevent
 * duplicate event records. Financial idempotency must additionally be
 * enforced by the payment processing service.</p>
 *
 * <p>Webhook events must be authenticated before a record is created.
 * A stored event does not automatically indicate successful processing.</p>
 */
@Document(collection = "stripe_webhook_events")
public class StripeWebhookEvent {

    /**
     * Lifecycle states for an authenticated Stripe webhook event.
     */
    public enum ProcessingStatus {
        RECEIVED,
        PROCESSING,
        PROCESSED,
        FAILED
    }

    @Id
    private String eventId;

    private String eventType;

    private ProcessingStatus status;

    private Instant createdAt;

    private Instant processedAt;

    /**
     * Payment associated with a successfully processed event.
     */
    private String paymentId;

    /**
     * Verified Stripe Checkout Session associated with the event.
     */
    private String checkoutSessionId;

    @Version
    private Long version;

    /**
     * Required by Spring Data MongoDB.
     */
    public StripeWebhookEvent() {
    }

    /**
     * Creates a newly received, authenticated webhook event.
     *
     * @param eventId unique Stripe event identifier
     * @param eventType Stripe event type
     */
    public StripeWebhookEvent(String eventId, String eventType) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException(
                    "Stripe event ID is required");
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "Stripe event type is required");
        }

        this.eventId = eventId.trim();
        this.eventType = eventType.trim();
        this.status = ProcessingStatus.RECEIVED;
        this.createdAt = Instant.now();
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Long getVersion() {
        return version;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getCheckoutSessionId() {
        return checkoutSessionId;
    }

    /**
     * Updates the processing state.
     *
     * <p>Only the processing service should call this method.
     * Transition validation and concurrency handling belong to
     * the service layer.</p>
     *
     * @param status new processing state
     */
    public void setStatus(ProcessingStatus status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "Webhook processing status is required");
        }

        this.status = status;

        if (status == ProcessingStatus.PROCESSED) {
            this.processedAt = Instant.now();
        } else {
            this.processedAt = null;
        }
    }
}