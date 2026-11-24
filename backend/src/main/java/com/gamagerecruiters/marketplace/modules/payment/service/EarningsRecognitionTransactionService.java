package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Coordinates initial earnings recognition with payment refund reservations.
 *
 * Both workflows conditionally write to the same payment document,
 * allowing MongoDB transaction conflict detection to protect against
 * concurrent financial state changes.
 *
 * Booking ownership and verified completion must be validated by
 * the calling lifecycle service before invoking this service.
 */
@Service
public class EarningsRecognitionTransactionService {

    private final MongoTemplate mongoTemplate;
    private final EarningsLedgerService earningsLedgerService;
    private final EarningsLedgerTransactionService ledgerTransactionService;
    private final TransactionTemplate transactionTemplate;

    public EarningsRecognitionTransactionService(
            MongoTemplate mongoTemplate,
            EarningsLedgerService earningsLedgerService,
            EarningsLedgerTransactionService ledgerTransactionService,
            PlatformTransactionManager transactionManager) {

        this.mongoTemplate =
                Objects.requireNonNull(mongoTemplate);

        this.earningsLedgerService =
                Objects.requireNonNull(earningsLedgerService);

        this.ledgerTransactionService =
                Objects.requireNonNull(ledgerTransactionService);

        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
    }

    /**
     * Records a validated initial earnings event atomically.
     *
     * The payment snapshot must remain unchanged between lifecycle
     * validation and transactional earnings recognition.
     *
     * Transaction failures propagate to the caller. Individual ledger
     * writes must not be retried independently within this transaction.
     */
    public EarningsLedgerEntry recognizeAtomically(
            Payment payment,
            EarningsLedgerEntry entry) {

        Objects.requireNonNull(payment, "Payment is required");
        Objects.requireNonNull(entry, "Earnings event is required");

        validateRecognitionRequest(payment, entry);

        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {

                    requireActiveMongoTransaction();

                    // Acquire a transactional write on the payment.
                    claimPaymentSnapshot(payment);

                    // Preserve existing event-key idempotency.
                    EarningsLedgerEntry existing =
                            earningsLedgerService
                                    .findMatchingCommittedEvent(entry)
                                    .orElse(null);

                    if (existing != null) {
                        return existing;
                    }

                    return ledgerTransactionService.appendAtomically(
                            entry.copyForAppend());
                }));
    }

    /**
     * Performs a conditional payment write inside the transaction.
     *
     * A unique write marker guarantees that this operation is not
     * optimized into a no-op when timestamps happen to match.
     */
    private void claimPaymentSnapshot(Payment payment) {

        Query query = Query.query(
                Criteria.where("_id").is(payment.getId())
                        .and("bookingId").is(payment.getBookingId())
                        .and("customerId").is(payment.getCustomerId())
                        .and("status").is(payment.getStatus())
                        .and("amount").is(payment.getAmount())
                        .and("commissionAmount")
                        .is(payment.getCommissionAmount())
                        .and("refundedAmount")
                        .is(payment.getRefundedAmount())
                        .and("currency").is(payment.getCurrency())
                        .and("gatewayPaymentIntentId")
                        .is(payment.getGatewayPaymentIntentId())
                        .and("refundReservationToken").is(null)
                        .and("checkoutReservationToken").is(null));

        Update update = new Update()
                .set("earningsRecognitionWriteToken",
                        UUID.randomUUID().toString())
                .set("updatedAt", Instant.now());

        long modifiedCount = mongoTemplate
                .updateFirst(query, update, Payment.class)
                .getModifiedCount();

        if (modifiedCount != 1) {

            throw new IllegalStateException(
                    "Payment changed or has an active financial reservation");
        }
    }

    /**
     * Validates the structural relationship between the payment
     * snapshot and its initial provider earnings event.
     */
    private void validateRecognitionRequest(
            Payment payment,
            EarningsLedgerEntry entry) {

        entry.validate();

        if (payment.getStatus() != PaymentStatus.CAPTURED) {

            throw new IllegalStateException(
                    "Initial earnings recognition requires a CAPTURED payment");
        }

        if (payment.getRefundReservationToken() != null
                || payment.getCheckoutReservationToken() != null) {

            throw new IllegalStateException(
                    "Payment has an active financial reservation");
        }

        if (payment.getRefundedAmount() != null
                && payment.getRefundedAmount().signum() != 0) {

            throw new IllegalStateException(
                    "Refund-adjusted earnings recognition is not yet supported");
        }

        if (entry.getEventType()
                    != EarningsLedgerEventType.EARNING_PENDING

                || !Objects.equals(
                        payment.getId(), entry.getPaymentId())

                || !Objects.equals(
                        payment.getBookingId(), entry.getBookingId())

                || !Objects.equals(
                        payment.getCurrency(), entry.getCurrency())

                || !Objects.equals(
                        "earning-pending:" + payment.getId(),
                        entry.getEventKey())) {

            throw new IllegalStateException(
                    "Earnings event does not match the payment snapshot");
        }

        if (payment.getAmount() == null
                || payment.getAmount().signum() <= 0
                || payment.getCommissionAmount() == null
                || payment.getCommissionAmount().signum() < 0
                || payment.getCommissionAmount()
                        .compareTo(payment.getAmount()) > 0) {

            throw new IllegalStateException(
                    "Invalid payment financial snapshot");
        }

        if (payment.getAmount()
                .subtract(payment.getCommissionAmount())
                .compareTo(entry.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Earnings amount does not match the payment snapshot");
        }
    }

    /**
     * Ensures that the payment write and ledger append participate
     * in an active MongoDB transaction.
     */
    private void requireActiveMongoTransaction() {

        if (!TransactionSynchronizationManager
                    .isActualTransactionActive()

                || !TransactionSynchronizationManager.hasResource(
                        mongoTemplate.getMongoDatabaseFactory())) {

            throw new IllegalStateException(
                    "Earnings recognition requires an active MongoDB transaction");
        }
    }
}