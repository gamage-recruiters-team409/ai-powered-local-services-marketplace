package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.payment.gateway.StripeWebhookVerifier;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;

/**
 * Validates authenticated Stripe Checkout events before financial processing.
 *
 * <p>Only signed events containing a paid Checkout Session are eligible
 * for capture. Unsupported event types are ignored without modifying
 * financial records.</p>
 */
@Service
public class StripeWebhookProcessingService {

    private static final Set<String> CAPTURE_EVENT_TYPES = Set.of(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded");

    private final StripeWebhookVerifier verifier;
    private final StripeWebhookEventRegistrationService registrationService;
    private final StripeWebhookTransactionService transactionService;

    public StripeWebhookProcessingService(
            StripeWebhookVerifier verifier,
            StripeWebhookEventRegistrationService registrationService,
            StripeWebhookTransactionService transactionService) {

        this.verifier = java.util.Objects.requireNonNull(verifier);
        this.registrationService =
                java.util.Objects.requireNonNull(registrationService);
        this.transactionService =
                java.util.Objects.requireNonNull(transactionService);
    }

    /**
     * Processes a raw Stripe webhook request after signature verification.
     *
     * @param rawPayload exact HTTP request body
     * @param signature Stripe-Signature header
     * @return true if a new payment capture was committed
     * @throws SignatureVerificationException if signature verification fails
     */
    public boolean process(String rawPayload, String signature)
            throws SignatureVerificationException {

        Event event = verifier.verify(rawPayload, signature);

        if (!CAPTURE_EVENT_TYPES.contains(event.getType())) {
            return false;
        }

        StripeObject stripeObject = event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to deserialize verified Stripe Checkout Session"));

        if (!(stripeObject instanceof Session session)) {
            throw new IllegalStateException(
                    "Stripe webhook does not contain a Checkout Session");
        }

        if (!"paid".equals(session.getPaymentStatus())) {
            return false;
        }

        String eventId = requireText(event.getId(), "Stripe event ID");
        String sessionId = requireText(session.getId(), "Checkout Session ID");
        String paymentId = requireText(
                session.getClientReferenceId(), "Payment reference");
        String currency = requireText(
                session.getCurrency(), "Checkout currency")
                .toUpperCase(Locale.ROOT);

        Long amountMinor = session.getAmountTotal();

        if (amountMinor == null || amountMinor <= 0) {
            throw new IllegalStateException(
                    "Stripe Checkout amount must be positive");
        }

        // Current gateway supports two-decimal currencies only.
        if (!Set.of("USD", "EUR", "GBP", "LKR").contains(currency)) {
            throw new IllegalStateException(
                    "Unsupported Stripe Checkout currency");
        }

        BigDecimal amount = BigDecimal.valueOf(
                amountMinor, 2);

        registrationService.register(event);

        // The transaction service must additionally validate the
        // persisted Payment ID against the verified payment reference.
        return transactionService.captureVerifiedPayment(
                eventId, paymentId, sessionId, amount, currency);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is missing");
        }
        return value.trim();
    }
}