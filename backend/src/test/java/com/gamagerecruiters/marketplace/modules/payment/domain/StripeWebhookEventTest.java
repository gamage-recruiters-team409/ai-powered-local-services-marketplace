package com.gamagerecruiters.marketplace.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent.ProcessingStatus;

/**
 * Unit tests for the Stripe webhook event domain model.
 *
 * <p>These tests verify local domain behavior only.
 * MongoDB persistence and concurrent processing require separate tests.</p>
 */
class StripeWebhookEventTest {

    @Test
    void validEventStartsInReceivedState() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_test_123",
                "checkout.session.completed");

        assertEquals("evt_test_123", event.getEventId());
        assertEquals("checkout.session.completed", event.getEventType());
        assertEquals(ProcessingStatus.RECEIVED, event.getStatus());
        assertNotNull(event.getCreatedAt());
        assertNull(event.getProcessedAt());
        assertNull(event.getVersion());
    }

    @Test
    void eventIdentifiersAreTrimmed() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "  evt_test_123  ",
                "  payment_intent.succeeded  ");

        assertEquals("evt_test_123", event.getEventId());
        assertEquals("payment_intent.succeeded", event.getEventType());
    }

    @Test
    void nullEventIdIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StripeWebhookEvent(
                        null,
                        "checkout.session.completed"));
    }

    @Test
    void blankEventIdIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StripeWebhookEvent(
                        "   ",
                        "checkout.session.completed"));
    }

    @Test
    void nullEventTypeIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StripeWebhookEvent(
                        "evt_test_123",
                        null));
    }

    @Test
    void blankEventTypeIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StripeWebhookEvent(
                        "evt_test_123",
                        "   "));
    }

    @Test
    void processingStateDoesNotSetCompletionTimestamp() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_test_123",
                "checkout.session.completed");

        event.setStatus(ProcessingStatus.PROCESSING);

        assertEquals(ProcessingStatus.PROCESSING, event.getStatus());
        assertNull(event.getProcessedAt());
    }

    @Test
    void processedStateSetsCompletionTimestamp() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_test_123",
                "checkout.session.completed");

        event.setStatus(ProcessingStatus.PROCESSED);

        assertEquals(ProcessingStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
    }

    @Test
    void failedStateClearsCompletionTimestamp() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_test_123",
                "checkout.session.completed");

        event.setStatus(ProcessingStatus.PROCESSED);
        assertNotNull(event.getProcessedAt());

        event.setStatus(ProcessingStatus.FAILED);

        assertEquals(ProcessingStatus.FAILED, event.getStatus());
        assertNull(event.getProcessedAt());
    }

    @Test
    void nullStatusIsRejectedWithoutChangingState() {
        StripeWebhookEvent event = new StripeWebhookEvent(
                "evt_test_123",
                "checkout.session.completed");

        assertThrows(
                IllegalArgumentException.class,
                () -> event.setStatus(null));

        assertEquals(ProcessingStatus.RECEIVED, event.getStatus());
    }

    @Test
    void eventUsesExpectedMongoCollectionAndAnnotations()
            throws NoSuchFieldException {

        Document document = StripeWebhookEvent.class
                .getAnnotation(Document.class);

        assertNotNull(document);
        assertEquals("stripe_webhook_events", document.collection());

        assertTrue(
                StripeWebhookEvent.class
                        .getDeclaredField("eventId")
                        .isAnnotationPresent(Id.class));

        assertTrue(
                StripeWebhookEvent.class
                        .getDeclaredField("version")
                        .isAnnotationPresent(Version.class));
    }
}