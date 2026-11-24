package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundResult;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Coordinates reserved refund operations and external gateway execution.
 *
 * Gateway requests are executed outside MongoDB transactions.
 * Only one caller may claim a pending operation for execution.
 *
 * Uncertain outcomes retain their financial reservations and
 * require reconciliation before further financial processing.
 *
 * This internal service does not authorize external API callers.
 */
@Service
public class RefundExecutionService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RefundExecutionService.class);

    private final RefundTransactionService refundTransactionService;
    private final RefundFinalizationService finalizationService;
    private final PaymentRefundGateway refundGateway;
    private final RefundOperationRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final MongoTemplate mongoTemplate;

    public RefundExecutionService(
            RefundTransactionService refundTransactionService,
            RefundFinalizationService finalizationService,
            PaymentRefundGateway refundGateway,
            RefundOperationRepository refundRepository,
            PaymentRepository paymentRepository,
            MongoTemplate mongoTemplate) {

        this.refundTransactionService =
                Objects.requireNonNull(refundTransactionService);

        this.finalizationService =
                Objects.requireNonNull(finalizationService);

        this.refundGateway =
                Objects.requireNonNull(refundGateway);

        this.refundRepository =
                Objects.requireNonNull(refundRepository);

        this.paymentRepository =
                Objects.requireNonNull(paymentRepository);

        this.mongoTemplate =
                Objects.requireNonNull(mongoTemplate);
    }

    /**
     * Reserves and executes an internally authorized refund.
     *
     * Existing operations are reused through their stable
     * business idempotency key.
     *
     * Processing, reconciliation and terminal operations are
     * never automatically submitted to the gateway again.
     */
    public RefundOperation executeRefund(
            String paymentId,
            String idempotencyKey,
            BigDecimal amount) {

        requireNoActiveTransaction();

        RefundOperation operation =
                refundTransactionService.reserveRefund(
                        paymentId,
                        idempotencyKey,
                        amount);

        return executeReservedRefund(operation.getId());
    }

    /**
     * Executes a previously persisted financial reservation.
     *
     * The conditional claim prevents concurrent callers from
     * submitting the same operation to the gateway.
     */
    public RefundOperation executeReservedRefund(
            String operationId) {

        requireNoActiveTransaction();

        String normalizedId =
                requireText(operationId, "Refund operation ID");

        RefundOperation existing =
                findOperation(normalizedId);

        // Never automatically replay an uncertain or completed refund.
        if (existing.getStatus() != RefundStatus.PENDING) {
            return existing;
        }

        validateExecutionReservation(existing);

        RefundOperation claimed =
                claimPendingOperation(existing);

        if (claimed == null) {

            // Another caller has already changed the operation.
            return findOperation(normalizedId);
        }

        final RefundResult result;

        try {

            // Revalidate the persisted reservation after claiming.
            validateExecutionReservation(claimed);

            requireNoActiveTransaction();

            result = refundGateway.createRefund(
                    claimed.getId(),
                    claimed.getGatewayPaymentReference(),
                    claimed.getAmount(),
                    claimed.getCurrency());

            Objects.requireNonNull(
                    result, "Gateway refund result is required");

        } catch (RuntimeException exception) {

            /*
             * An exception does not prove that the gateway failed
             * to execute the refund. Preserve all reservations.
             */
            LOGGER.warn(
                    "Refund execution requires reconciliation. Operation ID: {}",
                    normalizedId);

            return finalizationService
                    .markRequiresReconciliation(normalizedId);
        }

        try {

            return finalizationService.finalizeRefund(
                    normalizedId,
                    result);

        } catch (RuntimeException exception) {

            /*
             * The external refund may already have succeeded.
             * A local persistence failure must not release funds
             * or trigger another gateway refund.
             */
            LOGGER.error(
                    "Refund finalization requires reconciliation. Operation ID: {}",
                    normalizedId);

            return finalizationService
                    .markRequiresReconciliation(normalizedId);
        }
    }

    /**
     * Claims a pending operation using a conditional atomic update.
     *
     * The persisted version is checked and incremented explicitly
     * to remain compatible with optimistic locking.
     */
    private RefundOperation claimPendingOperation(
            RefundOperation operation) {

        Long version = Objects.requireNonNull(
                operation.getVersion(),
                "Persisted refund version is required");

        Query query = Query.query(
                Criteria.where("_id").is(operation.getId())
                        .and("status").is(RefundStatus.PENDING)
                        .and("version").is(version)
                        .and("paymentId").is(operation.getPaymentId())
                        .and("idempotencyKey")
                        .is(operation.getIdempotencyKey())
                        .and("gatewayRefundReference").is(null));

        Update update = new Update()
                .set("status", RefundStatus.PROCESSING)
                .set("updatedAt", Instant.now())
                .inc("version", 1L);

        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                RefundOperation.class);
    }

    /**
     * Verifies that the refund operation owns an active payment
     * reservation before any external financial request.
     */
    private void validateExecutionReservation(
            RefundOperation operation) {

        requireText(operation.getId(), "Refund operation ID");

        requireText(
                operation.getGatewayPaymentReference(),
                "Gateway payment reference");

        requireText(operation.getCurrency(), "Refund currency");

        RefundReservation reservation =
                Objects.requireNonNull(
                        operation.getReservation(),
                        "Persisted refund reservation is required");

        if (operation.getAmount() == null
                || operation.getAmount().signum() <= 0
                || operation.getAmount().scale() > 2) {

            throw new IllegalStateException(
                    "Invalid persisted refund amount");
        }

        if (operation.getGatewayRefundReference() != null) {

            throw new IllegalStateException(
                    "Refund already has a gateway reference");
        }

        // Ordinary refunds must never consume dispute-held earnings.
        // Dispute refunds require the shared settlement integrity checks.
        if (operation.getDisputeId() == null
                && reservation.heldAllocation().signum() != 0) {

            throw new IllegalStateException(
                    "Ordinary refund cannot consume dispute-held earnings");
        }

        if (reservation.unallocatedProviderReversalAmount()
                .signum() != 0) {

            throw new IllegalStateException(
                    "Unrecognized earnings refund is not supported");
        }

        if (reservation.commissionReversalAmount()
                .add(reservation.providerEarningsReversalAmount())
                .compareTo(operation.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Refund reservation amounts are inconsistent");
        }

        Payment payment = paymentRepository
                .findById(operation.getPaymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Refund payment not found"));

        if (payment.getStatus() != PaymentStatus.REFUND_PENDING

                || !Objects.equals(
                        operation.getIdempotencyKey(),
                        payment.getRefundReservationToken())

                || !Objects.equals(
                        operation.getBookingId(),
                        payment.getBookingId())

                || !operation.getCurrency().equalsIgnoreCase(
                        payment.getCurrency())

                || !operation.getGatewayPaymentReference().equals(
                        payment.getGatewayPaymentIntentId())

                || payment.getPaidAt() == null

                || payment.getRefundedAmount() == null

                || payment.getRefundedAmount().compareTo(
                        reservation.previouslyRefundedAmount()) != 0) {

            throw new IllegalStateException(
                    "Refund operation does not own an active payment reservation");
        }

        if (payment.getAmount() == null

                || payment.getRefundedAmount()
                        .add(operation.getAmount())
                        .compareTo(payment.getAmount()) > 0) {

            throw new IllegalStateException(
                    "Refund exceeds remaining refundable payment amount");
        }

        if (reservation.originalPaymentStatus()
                    != PaymentStatus.CAPTURED

                && reservation.originalPaymentStatus()
                    != PaymentStatus.PARTIALLY_REFUNDED) {

            throw new IllegalStateException(
                    "Invalid original payment status");
        }

        // Verify committed ledger reservations and commission calculations.
        RefundReservationIntegrityValidator.validate(
                mongoTemplate,
                payment,
                operation,
                reservation);
    }

    /**
     * Prevents external gateway execution inside a transaction.
     *
     * Financial reservations must be committed before contacting
     * the external payment gateway.
     */
    private void requireNoActiveTransaction() {

        if (TransactionSynchronizationManager
                    .isActualTransactionActive()

                || TransactionSynchronizationManager.hasResource(
                        mongoTemplate.getMongoDatabaseFactory())) {

            throw new IllegalStateException(
                    "Refund gateway execution requires committed reservations outside a transaction");
        }
    }

    private RefundOperation findOperation(String operationId) {

        return refundRepository.findById(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Refund operation not found"));
    }

    private static String requireText(
            String value, String fieldName) {

        if (value == null || value.isBlank()) {

            throw new IllegalArgumentException(
                    fieldName + " is required");
        }

        return value.trim();
    }
}