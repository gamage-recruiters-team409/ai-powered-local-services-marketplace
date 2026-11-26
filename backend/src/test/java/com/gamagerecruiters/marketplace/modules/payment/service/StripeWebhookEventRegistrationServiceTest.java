package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;

import com.stripe.model.Event;

import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent;
import com.gamagerecruiters.marketplace.modules.payment.repository.StripeWebhookEventRepository;

/**
 * Unit tests for Stripe webhook event registration.
 *
 * <p>Tests use a mocked repository and do not require MongoDB,
 * Docker, or a live Stripe connection.</p>
 */
class StripeWebhookEventRegistrationServiceTest {

    private StripeWebhookEventRepository repository;
    private StripeWebhookEventRegistrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(StripeWebhookEventRepository.class);
        service = new StripeWebhookEventRegistrationService(repository);
    }

    /**
     * Creates a mocked Stripe event with a fixed ID and type.
     */
    private Event verifiedEvent(String eventId, String eventType) {
        Event event = mock(Event.class);

        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(eventType);

        return event;
    }

    @Test
    void newEventIsInsertedSuccessfully() {
        Event event = verifiedEvent(
                "evt_test_123",
                "checkout.session.completed");

        when(repository.insert(any(StripeWebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StripeWebhookEvent result = service.register(event);

        assertEquals("evt_test_123", result.getEventId());
        assertEquals("checkout.session.completed", result.getEventType());
        assertEquals(
                StripeWebhookEvent.ProcessingStatus.RECEIVED,
                result.getStatus());

        verify(repository).insert(any(StripeWebhookEvent.class));
        verify(repository, never()).save(any(StripeWebhookEvent.class));
    }

    @Test
    void duplicateEventReturnsExistingRecord() {
        Event event = verifiedEvent(
                "evt_test_123",
                "checkout.session.completed");

        StripeWebhookEvent existing = new StripeWebhookEvent(
                "evt_test_123",
                "checkout.session.completed");

        existing.setStatus(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);

        when(repository.insert(any(StripeWebhookEvent.class)))
                .thenThrow(new DuplicateKeyException("Duplicate event"));

        when(repository.findById("evt_test_123"))
                .thenReturn(Optional.of(existing));

        StripeWebhookEvent result = service.register(event);

        assertSame(existing, result);
        assertEquals(
                StripeWebhookEvent.ProcessingStatus.PROCESSED,
                result.getStatus());

        verify(repository, never()).save(any(StripeWebhookEvent.class));
    }

    @Test
    void duplicateEventWithDifferentTypeIsRejected() {
        Event event = verifiedEvent(
                "evt_test_123",
                "checkout.session.completed");

        StripeWebhookEvent existing = new StripeWebhookEvent(
                "evt_test_123",
                "payment_intent.succeeded");

        when(repository.insert(any(StripeWebhookEvent.class)))
                .thenThrow(new DuplicateKeyException("Duplicate event"));

        when(repository.findById("evt_test_123"))
                .thenReturn(Optional.of(existing));

        assertThrows(
                IllegalStateException.class,
                () -> service.register(event));
    }

    @Test
    void duplicateEventWithoutExistingRecordIsRejected() {
        Event event = verifiedEvent(
                "evt_test_123",
                "checkout.session.completed");

        when(repository.insert(any(StripeWebhookEvent.class)))
                .thenThrow(new DuplicateKeyException("Duplicate event"));

        when(repository.findById("evt_test_123"))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> service.register(event));
    }

    @Test
    void nullEventIsRejectedWithoutRepositoryAccess() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.register(null));

        verifyNoInteractions(repository);
    }

    @Test
    void missingEventIdIsRejected() {
        Event event = verifiedEvent(
                null,
                "checkout.session.completed");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.register(event));

        verifyNoInteractions(repository);
    }

    @Test
    void blankEventTypeIsRejected() {
        Event event = verifiedEvent(
                "evt_test_123",
                "   ");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.register(event));

        verifyNoInteractions(repository);
    }

    @Test
    void databaseFailureIsPropagated() {
        Event event = verifiedEvent(
                "evt_test_123",
                "checkout.session.completed");

        when(repository.insert(any(StripeWebhookEvent.class)))
                .thenThrow(new DataAccessResourceFailureException(
                        "Database unavailable"));

        assertThrows(
                DataAccessResourceFailureException.class,
                () -> service.register(event));

        verify(repository, never()).findById(any());
    }
}