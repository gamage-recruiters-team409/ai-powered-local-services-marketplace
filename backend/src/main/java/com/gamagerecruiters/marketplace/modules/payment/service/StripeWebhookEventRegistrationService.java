package com.gamagerecruiters.marketplace.modules.payment.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.stripe.model.Event;

import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent;
import com.gamagerecruiters.marketplace.modules.payment.repository.StripeWebhookEventRepository;

/**
 * Registers authenticated Stripe webhook events for subsequent processing.
 *
 * <p>Only events that have passed Stripe signature verification should
 * be supplied to this service.</p>
 *
 * <p>Registration is idempotent by Stripe event ID. This service does
 * not execute financial operations or mark events as processed.</p>
 */
@Service
public class StripeWebhookEventRegistrationService {

    private final StripeWebhookEventRepository repository;

    /**
     * Creates the registration service.
     *
     * @param repository Stripe webhook event repository
     */
    public StripeWebhookEventRegistrationService(
            StripeWebhookEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers an authenticated Stripe event exactly once by event ID.
     *
     * <p>MongoDB insert is used instead of save to avoid overwriting
     * an existing event record. A duplicate insert caused by concurrent
     * delivery is resolved by reading the existing record.</p>
     *
     * @param verifiedEvent event returned by Stripe signature verification
     * @return newly registered or previously registered event
     */
    public StripeWebhookEvent register(Event verifiedEvent) {
        if (verifiedEvent == null) {
            throw new IllegalArgumentException(
                    "Verified Stripe event is required");
        }

        String eventId = verifiedEvent.getId();
        String eventType = verifiedEvent.getType();

        StripeWebhookEvent newEvent =
                new StripeWebhookEvent(eventId, eventType);

        try {
            return repository.insert(newEvent);
        } catch (DuplicateKeyException exception) {
            StripeWebhookEvent existingEvent = repository
                    .findById(newEvent.getEventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Duplicate Stripe event was detected, "
                                    + "but its record could not be retrieved",
                            exception));

            if (!newEvent.getEventType()
                    .equals(existingEvent.getEventType())) {
                throw new IllegalStateException(
                        "Stripe event ID is already registered "
                                + "with a different event type");
            }

            return existingEvent;
        }
    }
}