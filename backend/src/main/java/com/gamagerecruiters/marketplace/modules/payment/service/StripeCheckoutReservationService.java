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
 * Reserves a payment attempt before creating a Stripe Checkout Session.
 *
 * <p>MongoDB performs the reservation as a single conditional update.
 * Only one concurrent request can claim an unreserved payment attempt.</p>
 */
@Service
public class StripeCheckoutReservationService {

    private final MongoTemplate mongoTemplate;

    public StripeCheckoutReservationService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
    }

    /**
     * Atomically claims an eligible payment attempt.
     *
     * @param paymentId persisted payment identifier
     * @return unique reservation token, or null when already reserved
     */
    public String tryReserve(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID is required");
        }

        String reservationToken = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();

        Query query = Query.query(
                Criteria.where("_id").is(paymentId.trim())
                        .and("status").is(PaymentStatus.INITIATED)
                        .and("gatewayRef").is(null)
                        .and("checkoutReservationToken").is(null));

        Update update = new Update()
                .set("checkoutReservationToken", reservationToken)
                .set("checkoutReservedAt", now)
                .set("updatedAt", now);

        long modified = mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount();

        return modified == 1 ? reservationToken : null;
    }

    /**
     * Releases a reservation only when it belongs to the expected claim.
     *
     * <p>This operation must only be used when Checkout Session creation
     * definitively failed before a gateway session was created. Ambiguous
     * gateway failures require reconciliation rather than automatic release.</p>
     *
     * @param paymentId persisted payment identifier
     * @param reservationToken unique token identifying the reservation
     * @return true if the matching reservation was released
     */
    public boolean releaseReservation(String paymentId, String reservationToken) {
        if (paymentId == null || paymentId.isBlank()
                || reservationToken == null || reservationToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Payment ID and reservation token are required");
        }

        Query query = Query.query(
                Criteria.where("_id").is(paymentId.trim())
                        .and("status").is(PaymentStatus.INITIATED)
                        .and("gatewayRef").is(null)
                        .and("checkoutReservationToken").is(reservationToken));

        Update update = new Update()
                .unset("checkoutReservationToken")
                .unset("checkoutReservedAt")
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount() == 1;
    }
}