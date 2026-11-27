package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Provides conditional payment-level reservation for refund operations.
 *
 * Only one active refund operation may reserve a payment at a time.
 * The calling financial transaction must persist the refund operation
 * and reserve any applicable provider earnings atomically.
 *
 * This service does not execute gateway refunds or reverse earnings.
 */
@Service
public class RefundReservationService {

    private final MongoTemplate mongoTemplate;

    public RefundReservationService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
    }

    /**
     * Claims a payment using the expected persisted financial snapshot.
     *
     * The reservation token must identify the persisted refund operation.
     * A stale snapshot or competing reservation causes the claim to fail.
     *
     * @param payment expected payment snapshot
     * @param reservationToken stable refund operation identifier
     * @return true when the conditional reservation succeeds
     */
    public boolean tryReserve(
            Payment payment,
            String reservationToken) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(
                        mongoTemplate.getMongoDatabaseFactory())) {
            throw new IllegalStateException(
                    "Refund reservation requires an active MongoDB transaction");
        }

        Objects.requireNonNull(payment, "Payment is required");

        String paymentId = requireText(
                payment.getId(), "Payment ID");

        String token = requireText(
                reservationToken, "Refund reservation token");

        String paymentIntentId = requireText(
                payment.getGatewayPaymentIntentId(),
                "Verified gateway PaymentIntent reference");

        PaymentStatus originalStatus = payment.getStatus();

        // Preserve the existing payment lifecycle policy.
        if (originalStatus != PaymentStatus.CAPTURED
                && originalStatus != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    "Payment status is not eligible for refund reservation");
        }

        Objects.requireNonNull(
                payment.getAmount(), "Payment amount is required");

        Objects.requireNonNull(
                payment.getCommissionAmount(),
                "Payment commission is required");

        Objects.requireNonNull(
                payment.getRefundedAmount(),
                "Previously refunded amount is required");

        // Compare the expected financial snapshot and reservation state.
        Query query = Query.query(
                Criteria.where("_id").is(paymentId)
                        .and("bookingId").is(payment.getBookingId())
                        .and("status").is(originalStatus)
                        .and("amount").is(payment.getAmount())
                        .and("currency").is(payment.getCurrency())
                        .and("commissionAmount")
                            .is(payment.getCommissionAmount())
                        .and("refundedAmount")
                            .is(payment.getRefundedAmount())
                        .and("gatewayPaymentIntentId").is(paymentIntentId)
                        .and("checkoutReservationToken").is(null)
                        .and("refundReservationToken").is(null));

        Instant now = Instant.now();

        Update update = new Update()
                .set("refundReservationToken", token)
                .set("refundReservedAt", now)
                .set("status", PaymentStatus.REFUND_PENDING)
                .set("updatedAt", now);

        // MongoDB performs the claim as a single conditional update.
        return mongoTemplate.updateFirst(
                query, update, Payment.class)
                .getModifiedCount() == 1;
    }

    /**
     * Validates a required non-blank identifier.
     */
    private static String requireText(
            String value,
            String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required");
        }

        return value.trim();
    }
}