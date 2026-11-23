package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Atomically associates a Stripe Checkout Session with a payment attempt.
 *
 * <p>The conditional update prevents overwriting an existing gateway
 * reference or attaching a session to a payment that has already
 * transitioned out of INITIATED.</p>
 */
@Service
public class StripeCheckoutSessionPersistenceService {

    private final MongoTemplate mongoTemplate;

    public StripeCheckoutSessionPersistenceService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(
                mongoTemplate, "MongoTemplate is required");
    }

    /**
     * Stores a gateway session reference only for an eligible payment.
     *
     * @param paymentId persisted payment identifier
     * @param sessionId verified gateway session identifier
     * @param reservationToken token identifying the active reservation
     * @throws IllegalStateException when the conditional update fails
     */
    public void attachSession(
            String paymentId,
            String sessionId,
            String reservationToken) {

        requireText(paymentId, "Payment ID");
        requireText(sessionId, "Checkout Session ID");
        requireText(reservationToken, "Checkout reservation token");

        Query query = Query.query(
                Criteria.where("_id").is(paymentId.trim())
                        .and("status").is(PaymentStatus.INITIATED)
                        .and("gatewayRef").is(null)
                        .and("checkoutReservationToken").is(
                                reservationToken.trim()));

        Update update = new Update()
                .set("gatewayRef", sessionId.trim())
                .unset("checkoutReservationToken")
                .unset("checkoutReservedAt")
                .set("updatedAt", Instant.now());

        long modified = mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount();

        if (modified != 1) {
            throw new IllegalStateException(
                    "Checkout Session could not be attached to the payment");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}