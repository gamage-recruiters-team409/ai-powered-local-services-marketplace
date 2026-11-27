package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent;

/**
 * Atomically processes verified Stripe payment captures.
 *
 * <p>Webhook event claiming, payment capture and event completion
 * and booking confirmation are committed within one MongoDB
 * transaction.</p>
 *
 * <p>Only authenticated, validated Stripe payment data may be passed
 * to this service. It does not verify webhook signatures.</p>
 */
@Service
public class StripeWebhookTransactionService {

    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactionTemplate;
    private final BookingPaymentConfirmationService bookingConfirmationService;
    private final boolean bookingConfirmationEnabled;
    private final PaymentStatusTransitionService transitionService =
            new PaymentStatusTransitionService();

    public StripeWebhookTransactionService(
            MongoTemplate mongoTemplate,
            PlatformTransactionManager transactionManager,
            BookingPaymentConfirmationService bookingConfirmationService,
            @Value("${app.payment.booking-confirmation.enabled:false}") boolean bookingConfirmationEnabled) {

        this.mongoTemplate = Objects.requireNonNull(
                mongoTemplate, "MongoTemplate is required");

        this.bookingConfirmationService = Objects.requireNonNull(
                bookingConfirmationService,
                "Booking payment confirmation service is required");
        this.bookingConfirmationEnabled = bookingConfirmationEnabled;

        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager,
                        "Transaction manager is required"));
    }

    /**
     * Captures a verified, paid Stripe Checkout Session.
     *
     * @param eventId authenticated Stripe event identifier
     * @param paymentId verified internal payment identifier
     * @param sessionId verified Stripe Checkout Session identifier
     * @param paymentIntentId verified Stripe PaymentIntent identifier
     * @param amount verified Stripe payment amount in major units
     * @param currency verified ISO currency code
     * @return true if a new capture was committed, false if already processed
     */
    public boolean captureVerifiedPayment(
            String eventId,
            String paymentId,
            String sessionId,
            String paymentIntentId,
            BigDecimal amount,
            String currency) {

        String validatedEventId = requireText(eventId, "Event ID");
        String validatedPaymentId = requireText(paymentId, "Payment ID");
        String validatedSessionId = requireText(sessionId, "Session ID");
        String validatedPaymentIntentId = requireText(
                paymentIntentId, "PaymentIntent ID");

        if (!validatedPaymentIntentId.matches("pi_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Invalid Stripe PaymentIntent ID");
        }
        String validatedCurrency = requireText(currency, "Currency")
                .toUpperCase(java.util.Locale.ROOT);

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Verified payment amount must be positive");
        }

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {

            StripeWebhookEvent event = mongoTemplate.findById(
                    validatedEventId, StripeWebhookEvent.class);

            if (event == null) {
                throw new IllegalStateException(
                        "Verified webhook event is not registered");
            }

            if (event.getStatus()
                    == StripeWebhookEvent.ProcessingStatus.PROCESSED) {

                if (!validatedPaymentId.equals(event.getPaymentId())
                        || !validatedSessionId.equals(
                                event.getCheckoutSessionId())) {
                    throw new IllegalStateException(
                            "Processed Stripe event payment association mismatch");
                }

                Payment processedPayment = mongoTemplate.findOne(
                        Query.query(Criteria.where("gatewayRef")
                                .is(validatedSessionId)),
                        Payment.class);

                if (processedPayment == null
                        || !validatedPaymentId.equals(
                                processedPayment.getId())
                        || processedPayment.getAmount() == null
                        || processedPayment.getAmount().compareTo(amount) != 0
                        || !validatedCurrency.equalsIgnoreCase(
                                processedPayment.getCurrency())) {
                    throw new IllegalStateException(
                            "Processed Stripe event payment details mismatch");
                }

                // A processed event must never overwrite an existing
                // PaymentIntent or silently backfill a legacy record.
                validateStoredPaymentIntent(
                        processedPayment,
                        validatedPaymentIntentId,
                        false);

                return false;
            }

            if (event.getStatus()
                    != StripeWebhookEvent.ProcessingStatus.RECEIVED) {
                throw new IllegalStateException(
                        "Webhook event is not eligible for processing");
            }

            Payment payment = mongoTemplate.findOne(
                    Query.query(Criteria.where("gatewayRef")
                            .is(validatedSessionId)),
                    Payment.class);

            if (payment == null) {
                throw new IllegalStateException(
                        "Payment not found for Stripe Checkout Session");
            }

            if (!validatedPaymentId.equals(payment.getId())) {
                throw new IllegalStateException(
                        "Stripe payment reference does not match stored Payment ID");
            }

            if (payment.getAmount() == null
                    || payment.getAmount().compareTo(amount) != 0
                    || !validatedCurrency.equalsIgnoreCase(
                            payment.getCurrency())) {
                throw new IllegalStateException(
                        "Stripe payment amount or currency mismatch");
            }

            boolean alreadyCaptured =
                    payment.getStatus() == PaymentStatus.CAPTURED;

            if (!alreadyCaptured
                    && payment.getStatus() != PaymentStatus.INITIATED) {
                throw new IllegalStateException(
                        "Payment is not eligible for capture");
            }

            // Only an initiated payment may receive its first
            // PaymentIntent during a new verified capture.
            validateStoredPaymentIntent(
                    payment,
                    validatedPaymentIntentId,
                    !alreadyCaptured);

            if (!alreadyCaptured) {
                transitionService.validateTransition(
                    PaymentStatus.INITIATED,
                    PaymentStatus.AUTHORIZED);

                transitionService.validateTransition(
                        PaymentStatus.AUTHORIZED,
                        PaymentStatus.CAPTURED);
            }

            if (!claimReceivedEvent(validatedEventId)) {
                throw new IllegalStateException(
                        "Webhook event was concurrently claimed");
            }

            Instant now = Instant.now();

            Query paymentQuery = Query.query(
                    Criteria.where("_id").is(validatedPaymentId)
                            .and("status").is(PaymentStatus.INITIATED)
                            .and("gatewayRef").is(validatedSessionId)
                            .orOperator(
                                    Criteria.where("gatewayPaymentIntentId")
                                            .is(null),
                                    Criteria.where("gatewayPaymentIntentId")
                                            .is(validatedPaymentIntentId)));

            Update paymentUpdate = new Update()
                    .set("status", PaymentStatus.CAPTURED)
                    .set("gatewayPaymentIntentId", validatedPaymentIntentId)
                    // Issue a stable receipt reference only during verified capture.
                    .set("receiptReference", "RCT-" + validatedPaymentId)
                    .set("paidAt", now)
                    .set("updatedAt", now);

            if (!alreadyCaptured) {
                if (mongoTemplate.updateFirst(
                        paymentQuery,
                        paymentUpdate,
                        Payment.class).getModifiedCount() != 1) {
                    throw new IllegalStateException(
                            "Concurrent payment capture detected");
                }
            }

            // Synchronize the booking before completing the verified event.
            // A confirmation failure rolls back the local capture transaction.
            if (bookingConfirmationEnabled) {
                bookingConfirmationService.confirmVerifiedPayment(payment);
            }

            Query eventQuery = Query.query(
                    Criteria.where("_id").is(validatedEventId)
                            .and("status").is(
                                    StripeWebhookEvent.ProcessingStatus.PROCESSING));

            Update eventUpdate = new Update()
                    .set("status", StripeWebhookEvent.ProcessingStatus.PROCESSED)
                    .set("processedAt", now)
                    .set("paymentId", validatedPaymentId)
                    .set("checkoutSessionId", validatedSessionId);

            if (mongoTemplate.updateFirst(
                    eventQuery,
                    eventUpdate,
                    StripeWebhookEvent.class).getModifiedCount() != 1) {
                throw new IllegalStateException(
                        "Webhook completion update failed");
            }

            return !alreadyCaptured;
        }));
    }

    /**
     * Claims an event inside the active MongoDB transaction.
     */
    private boolean claimReceivedEvent(String eventId) {

        Query query = Query.query(
                Criteria.where("_id").is(eventId)
                        .and("status").is(
                                StripeWebhookEvent.ProcessingStatus.RECEIVED));

        Update update = new Update()
                .set("status", StripeWebhookEvent.ProcessingStatus.PROCESSING);

        return mongoTemplate.updateFirst(
                query,
                update,
                StripeWebhookEvent.class).getModifiedCount() == 1;
    }

    /**
     * Prevents conflicting PaymentIntent references and unsafe
     * backfilling of previously captured legacy payments.
     *
     * Legacy captured payments without a verified reference must
     * be handled through a dedicated reconciliation workflow.
     */
    private static void validateStoredPaymentIntent(
            Payment payment,
            String verifiedPaymentIntentId,
            boolean allowMissingReference) {

        String storedPaymentIntentId =
                payment.getGatewayPaymentIntentId();

        if (storedPaymentIntentId == null) {
            if (allowMissingReference) {
                return;
            }

            throw new IllegalStateException(
                    "Stored PaymentIntent reference is missing; "
                            + "verified reconciliation is required");
        }

        if (!verifiedPaymentIntentId.equals(storedPaymentIntentId)) {
            throw new IllegalStateException(
                    "Stripe PaymentIntent reference mismatch");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required");
        }
        return value.trim();
    }
}