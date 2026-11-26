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
                        .and("gatewayPaymentIntentId").is(null)
                        .and("checkoutReservedAt").is(null)
                        .and("checkoutSubmissionStarted").is(null)
                        .and("checkoutReservationToken").is(null));

        Update update = new Update()
                .set("checkoutReservationToken", reservationToken)
                .set("checkoutReservedAt", now)
                .set("checkoutSubmissionStarted", false)
                .set("updatedAt", now);

        long modified = mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount();

        return modified == 1 ? reservationToken : null;
    }

    /**
     * Atomically authorizes gateway submission for the current owner.
     * A recovered or replaced reservation cannot submit a request.
     */
    public boolean markSubmissionStarted(
            String paymentId, String reservationToken) {

        requireReservationIdentity(paymentId, reservationToken);

        Query query = Query.query(
                Criteria.where("_id").is(paymentId.trim())
                        .and("status").is(PaymentStatus.INITIATED)
                        .and("gatewayRef").is(null)
                        .and("gatewayPaymentIntentId").is(null)
                        .and("checkoutReservationToken").is(reservationToken)
                        .and("checkoutSubmissionStarted").is(false));

        Update update = new Update()
                .set("checkoutSubmissionStarted", true)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount() == 1;
    }

    /**
     * Releases an interrupted reservation only when gateway submission
     * was never authorized and the reservation is at least five minutes old.
     *
     * The atomic condition prevents a concurrent submission from racing
     * with recovery. Unknown legacy states are never released here.
     */
    public boolean releaseStaleUnsubmittedReservation(
            String paymentId, String reservationToken) {

        requireReservationIdentity(paymentId, reservationToken);

        Instant now = Instant.now();

        Query query = Query.query(
                Criteria.where("_id").is(paymentId.trim())
                        .and("status").is(PaymentStatus.INITIATED)
                        .and("gatewayRef").is(null)
                        .and("gatewayPaymentIntentId").is(null)
                        .and("checkoutReservationToken").is(reservationToken)
                        .and("checkoutSubmissionStarted").is(false)
                        .and("checkoutReservedAt").lte(now.minusSeconds(300)));

        Update update = new Update()
                .unset("checkoutReservationToken")
                .unset("checkoutReservedAt")
                .unset("checkoutSubmissionStarted")
                .set("updatedAt", now);

        return mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount() == 1;
    }

    private static void requireReservationIdentity(
            String paymentId, String reservationToken) {

        if (paymentId == null || paymentId.isBlank()
                || reservationToken == null || reservationToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Payment ID and reservation token are required");
        }
    }

    /**
     * Releases an authorized reservation belonging to the expected claim.
     *
     * Only the current initiation workflow may invoke this operation
     * after a definitive CheckoutNotSubmittedException.
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
                        .and("gatewayPaymentIntentId").is(null)
                        .and("checkoutSubmissionStarted").is(true)
                        .and("checkoutReservationToken").is(reservationToken));

        Update update = new Update()
                .unset("checkoutReservationToken")
                .unset("checkoutReservedAt")
                .unset("checkoutSubmissionStarted")
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(
                query, update, Payment.class).getModifiedCount() == 1;
    }
}