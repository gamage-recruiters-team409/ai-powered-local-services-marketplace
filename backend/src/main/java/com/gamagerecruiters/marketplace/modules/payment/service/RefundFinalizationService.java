package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundResult;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Finalizes verified refund outcomes within a financial transaction.
 *
 * Successful refunds settle reserved provider earnings.
 * Definitively failed refunds restore their original earnings balances.
 * Uncertain outcomes retain all financial reservations.
 *
 * This internal service must only receive trusted gateway results.
 * It does not perform external gateway calls or authorize API callers.
 */
@Service
public class RefundFinalizationService {

    private final PaymentRepository paymentRepository;
    private final RefundOperationRepository refundRepository;
    private final EarningsLedgerTransactionService ledgerTransactionService;
    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactionTemplate;

    public RefundFinalizationService(
            PaymentRepository paymentRepository,
            RefundOperationRepository refundRepository,
            EarningsLedgerTransactionService ledgerTransactionService,
            MongoTemplate mongoTemplate,
            PlatformTransactionManager transactionManager) {

        this.paymentRepository =
                Objects.requireNonNull(paymentRepository);

        this.refundRepository =
                Objects.requireNonNull(refundRepository);

        this.ledgerTransactionService =
                Objects.requireNonNull(ledgerTransactionService);

        this.mongoTemplate =
                Objects.requireNonNull(mongoTemplate);

        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
    }

    /**
     * Applies a verified gateway result atomically.
     *
     * Pending or unknown gateway outcomes must not release funds.
     */
    public RefundOperation finalizeRefund(
            String operationId,
            RefundResult result) {

        String normalizedId =
                requireText(operationId, "Refund operation ID");

        Objects.requireNonNull(result, "Gateway refund result is required");

        return Objects.requireNonNull(
                transactionTemplate.execute(status ->
                        finalizeAtomically(normalizedId, result)));
    }

    /**
     * Retains financial reservations when the gateway outcome
     * cannot be established.
     */
    public RefundOperation markRequiresReconciliation(
            String operationId) {

        String normalizedId =
                requireText(operationId, "Refund operation ID");

        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {

                    RefundOperation operation =
                            findOperation(normalizedId);

                    if (isTerminal(operation)) {
                        return operation;
                    }

                    requireProcessingState(operation);

                    if (operation.getStatus()
                            == RefundStatus.REQUIRES_RECONCILIATION) {

                        return operation;
                    }

                    operation.setStatus(
                            RefundStatus.REQUIRES_RECONCILIATION);

                    return refundRepository.save(operation);
                }));
    }

    /**
     * Performs payment, earnings and refund operation updates
     * within the same MongoDB transaction.
     */
    private RefundOperation finalizeAtomically(
            String operationId,
            RefundResult result) {

        RefundOperation operation =
                findOperation(operationId);

        validateGatewayResult(operation, result);

        if (isTerminal(operation)) {

            validateTerminalRetry(operation, result);

            return operation;
        }

        requireProcessingState(operation);

        RefundReservation reservation =
                Objects.requireNonNull(
                        operation.getReservation(),
                        "Persisted refund reservation is required");

        Payment payment = paymentRepository
                .findById(operation.getPaymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Refund payment not found"));

        validatePaymentReservation(
                payment, operation, reservation);

        // Verify committed ledger reservations and commission calculations.
        RefundReservationIntegrityValidator.validate(
                mongoTemplate,
                payment,
                operation,
                reservation);

        operation.setGatewayRefundReference(
                result.gatewayRefundReference());

        switch (result.status()) {

            case SUCCEEDED -> {

                settleSuccessfulRefund(
                        payment, operation, reservation);

                operation.setStatus(RefundStatus.SUCCEEDED);
            }

            case FAILED, CANCELED -> {

                restoreFailedRefund(
                        payment, operation, reservation);

                operation.setStatus(RefundStatus.FAILED);
            }

            case PENDING, REQUIRES_ACTION, UNKNOWN -> {

                // Retain the payment and earnings reservations.
                operation.setStatus(
                        RefundStatus.REQUIRES_RECONCILIATION);
            }
        }

        return refundRepository.save(operation);
    }

    /**
     * Settles reserved earnings and records the cumulative
     * successfully refunded payment amount.
     */
    private void settleSuccessfulRefund(
            Payment payment,
            RefundOperation operation,
            RefundReservation reservation) {

        BigDecimal cumulativeRefundedAmount =
                reservation.previouslyRefundedAmount()
                        .add(operation.getAmount());

        if (cumulativeRefundedAmount.compareTo(
                payment.getAmount()) > 0) {

            throw new IllegalStateException(
                    "Cumulative refund exceeds original payment amount");
        }

        PaymentStatus nextStatus =
                cumulativeRefundedAmount.compareTo(
                        payment.getAmount()) == 0

                        ? PaymentStatus.REFUNDED
                        : PaymentStatus.PARTIALLY_REFUNDED;

        appendLedgerEvent(
                operation,
                reservation,
                EarningsLedgerEventType.REFUND_SETTLE,
                reservation.totalAllocatedEarnings(),
                "REFUND_SETTLE");

        finalizeDisputeHold(operation, reservation, true);

        Update update = new Update()
                .set("refundedAmount", cumulativeRefundedAmount)
                .set("status", nextStatus)
                .set("refundReference",
                        operation.getGatewayRefundReference())
                .set("refundStatus", RefundStatus.SUCCEEDED.name())
                .set("updatedAt", Instant.now())
                .unset("refundReservationToken")
                .unset("refundReservedAt");

        updateReservedPayment(
                payment, operation, reservation, update);
    }

    /**
     * Restores reserved earnings to their original balance buckets.
     *
     * This recovery is permitted only for a verified terminal
     * gateway failure or cancellation.
     */
    private void restoreFailedRefund(
            Payment payment,
            RefundOperation operation,
            RefundReservation reservation) {

        appendLedgerEvent(
                operation,
                reservation,
                EarningsLedgerEventType.REFUND_RELEASE_TO_PENDING,
                reservation.pendingAllocation(),
                "REFUND_RELEASE_PENDING");

        appendLedgerEvent(
                operation,
                reservation,
                EarningsLedgerEventType.REFUND_RELEASE_TO_AVAILABLE,
                reservation.availableAllocation(),
                "REFUND_RELEASE_AVAILABLE");

        appendLedgerEvent(
                operation,
                reservation,
                EarningsLedgerEventType.REFUND_RELEASE_TO_HELD,
                reservation.heldAllocation(),
                "REFUND_RELEASE_HELD");

        finalizeDisputeHold(operation, reservation, false);

        // Preserve previously successful refund metadata.
        // The failed attempt is recorded in RefundOperation.
        Update update = new Update()
                .set("status", reservation.originalPaymentStatus())
                .set("updatedAt", Instant.now())
                .unset("refundReservationToken")
                .unset("refundReservedAt");

        updateReservedPayment(
                payment, operation, reservation, update);
    }

    /**
     * Updates only the payment owned by this refund reservation.
     *
     * A stale or conflicting payment update aborts the transaction.
     */

    /**
     * Finalizes a claimed dispute hold inside the existing
     * refund transaction.
     *
     * Successful refunds release only the remaining held earnings.
     * Definitive failures restore the original applied hold.
     */
    private void finalizeDisputeHold(
            RefundOperation operation,
            RefundReservation reservation,
            boolean successful) {

        if (operation.getDisputeId() == null) {
            return;
        }

        if (!TransactionSynchronizationManager
                    .isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(
                        mongoTemplate.getMongoDatabaseFactory())) {

            throw new IllegalStateException(
                    "Dispute finalization requires a MongoDB transaction");
        }

        String disputeId = operation.getDisputeId();

        DisputePayoutHold hold = mongoTemplate.findOne(
                Query.query(Criteria.where("disputeId").is(disputeId)),
                DisputePayoutHold.class);

        if (hold == null
                || hold.getStatus()
                    != DisputePayoutHold.HoldStatus.SETTLING
                || !Objects.equals(
                        operation.getPaymentId(), hold.getPaymentId())
                || !Objects.equals(
                        operation.getBookingId(), hold.getBookingId())
                || !Objects.equals(
                        reservation.providerId(), hold.getProviderId())
                || !Objects.equals(
                        operation.getCurrency(), hold.getCurrency())) {

            throw new IllegalStateException(
                    "Dispute settlement hold is inconsistent");
        }

        // Verify booking-specific balances after refund ledger updates.
        List<EarningsLedgerEntry> history = mongoTemplate.find(
                Query.query(Criteria.where("bookingId")
                        .is(operation.getBookingId())),
                EarningsLedgerEntry.class);

        if (history.isEmpty()) {
            throw new IllegalStateException(
                    "Dispute earnings history is missing");
        }

        for (EarningsLedgerEntry entry : history) {

            entry.validate();

            if (!Objects.equals(
                        operation.getPaymentId(), entry.getPaymentId())
                    || !Objects.equals(
                        operation.getBookingId(), entry.getBookingId())
                    || !Objects.equals(
                        reservation.providerId(), entry.getProviderId())
                    || !Objects.equals(
                        operation.getCurrency(), entry.getCurrency())) {

                throw new IllegalStateException(
                        "Dispute ledger ownership mismatch");
            }
        }

        EarningsBalanceCalculator.EarningsBalance balance =
                new EarningsBalanceCalculator().calculate(history);

        BigDecimal expectedHeld = successful
                ? hold.getAmount().subtract(
                        reservation.heldAllocation())
                : hold.getAmount();

        if (expectedHeld.signum() < 0
                || balance.held().compareTo(expectedHeld) != 0
                || balance.refundReserved().signum() != 0
                || balance.payoutReserved().signum() != 0) {

            throw new IllegalStateException(
                    "Dispute finalization balance mismatch");
        }

        if (successful) {

            releaseRemainingDisputeEarnings(
                    operation, reservation, hold);

            hold.markSettled();

        } else {

            // The refund release event has restored held earnings.
            hold.restoreAppliedAfterSettlementFailure();
        }

        // Reject concurrent or stale hold state changes.
        long modified = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(hold.getId())
                        .and("status").is(
                                DisputePayoutHold.HoldStatus.SETTLING)),
                new Update()
                        .set("status", hold.getStatus())
                        .set("updatedAt", Instant.now()),
                DisputePayoutHold.class)
                .getModifiedCount();

        if (modified != 1) {
            throw new IllegalStateException(
                    "Dispute hold changed during finalization");
        }
    }

    /**
     * Consumes the refund deduction from pending-origin held
     * earnings first, then available-origin held earnings.
     * Only the unconsumed remainder is released.
     */
    private void releaseRemainingDisputeEarnings(
            RefundOperation operation,
            RefundReservation reservation,
            DisputePayoutHold hold) {

        String holdKey = "DISPUTE_HOLD:" + hold.getDisputeId();

        EarningsLedgerEntry primary = mongoTemplate.findOne(
                Query.query(Criteria.where("eventKey").is(holdKey)),
                EarningsLedgerEntry.class);

        EarningsLedgerEntry secondary = mongoTemplate.findOne(
                Query.query(Criteria.where("eventKey")
                        .is(holdKey + ":AVAILABLE")),
                EarningsLedgerEntry.class);

        if (primary == null) {
            throw new IllegalStateException(
                    "Original dispute hold event is missing");
        }

        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;

        if (primary.getEventType()
                == EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING) {

            pending = primary.getAmount();

        } else if (primary.getEventType()
                == EarningsLedgerEventType.EARNING_HOLD) {

            available = primary.getAmount();

        } else {
            throw new IllegalStateException(
                    "Unsupported original hold event");
        }

        if (secondary != null) {

            if (secondary.getEventType()
                    != EarningsLedgerEventType.EARNING_HOLD
                    || pending.signum() <= 0) {

                throw new IllegalStateException(
                        "Invalid secondary hold event");
            }

            available = available.add(secondary.getAmount());
        }

        if (pending.add(available).compareTo(hold.getAmount()) != 0) {
            throw new IllegalStateException(
                    "Original dispute hold total mismatch");
        }

        BigDecimal deduction = reservation.heldAllocation();

        BigDecimal pendingDeduction = pending.min(deduction);

        BigDecimal availableDeduction =
                deduction.subtract(pendingDeduction);

        BigDecimal remainingPending =
                pending.subtract(pendingDeduction);

        BigDecimal remainingAvailable =
                available.subtract(availableDeduction);

        if (remainingPending.signum() < 0
                || remainingAvailable.signum() < 0) {

            throw new IllegalStateException(
                    "Refund deduction exceeds dispute hold");
        }

        appendLedgerEvent(
                operation,
                reservation,
                EarningsLedgerEventType.EARNING_RELEASE_TO_PENDING,
                remainingPending,
                "DISPUTE_REFUND_REMAINDER_PENDING");

        appendLedgerEvent(
                operation,
                reservation,
                EarningsLedgerEventType.EARNING_RELEASE,
                remainingAvailable,
                "DISPUTE_REFUND_REMAINDER_AVAILABLE");
    }

    private void updateReservedPayment(
            Payment payment,
            RefundOperation operation,
            RefundReservation reservation,
            Update update) {

        Query query = Query.query(
                Criteria.where("_id").is(payment.getId())
                        .and("bookingId").is(operation.getBookingId())
                        .and("status").is(PaymentStatus.REFUND_PENDING)
                        .and("refundReservationToken")
                        .is(operation.getIdempotencyKey())
                        .and("refundedAmount")
                        .is(reservation.previouslyRefundedAmount())
                        .and("amount").is(payment.getAmount())
                        .and("commissionAmount")
                        .is(payment.getCommissionAmount())
                        .and("currency").is(payment.getCurrency())
                        .and("gatewayPaymentIntentId")
                        .is(operation.getGatewayPaymentReference()));

        long modifiedCount = mongoTemplate
                .updateFirst(query, update, Payment.class)
                .getModifiedCount();

        if (modifiedCount != 1) {

            throw new IllegalStateException(
                    "Refund payment reservation changed during finalization");
        }
    }

    /**
     * Appends deterministic financial events inside the
     * surrounding refund transaction.
     */
    private void appendLedgerEvent(
            RefundOperation operation,
            RefundReservation reservation,
            EarningsLedgerEventType eventType,
            BigDecimal amount,
            String eventPrefix) {

        if (amount.signum() == 0) {
            return;
        }

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(
                eventPrefix + ":" + operation.getId());

        entry.setPaymentId(operation.getPaymentId());
        entry.setBookingId(operation.getBookingId());
        entry.setProviderId(reservation.providerId());
        entry.setCurrency(operation.getCurrency());
        entry.setEventType(eventType);
        entry.setAmount(amount);
        entry.setCreatedAt(Instant.now());

        entry.validate();

        ledgerTransactionService.appendAtomically(entry);
    }

    /**
     * Verifies that the gateway result belongs to the persisted
     * refund operation and matches its financial parameters.
     */
    private void validateGatewayResult(
            RefundOperation operation,
            RefundResult result) {

        requireText(
                result.gatewayRefundReference(),
                "Gateway refund reference");

        Objects.requireNonNull(
                result.status(), "Gateway refund status is required");

        if (!Objects.equals(
                operation.getGatewayPaymentReference(),
                result.gatewayPaymentReference())

                || result.amount() == null

                || operation.getAmount().compareTo(
                        result.amount()) != 0

                || !operation.getCurrency().equalsIgnoreCase(
                        result.currency())) {

            throw new IllegalStateException(
                    "Gateway refund result does not match persisted operation");
        }

        if (operation.getGatewayRefundReference() != null

                && !operation.getGatewayRefundReference().equals(
                        result.gatewayRefundReference())) {

            throw new IllegalStateException(
                    "Gateway refund reference cannot be replaced");
        }
    }

    /**
     * Validates ownership and consistency of the active
     * payment-level financial reservation.
     */
    private void validatePaymentReservation(
            Payment payment,
            RefundOperation operation,
            RefundReservation reservation) {

        if (payment.getStatus() != PaymentStatus.REFUND_PENDING

                || !operation.getIdempotencyKey().equals(
                        payment.getRefundReservationToken())

                || !operation.getBookingId().equals(
                        payment.getBookingId())

                || !operation.getCurrency().equalsIgnoreCase(
                        payment.getCurrency())

                || !operation.getGatewayPaymentReference().equals(
                        payment.getGatewayPaymentIntentId())

                || payment.getRefundedAmount() == null

                || payment.getRefundedAmount().compareTo(
                        reservation.previouslyRefundedAmount()) != 0) {

            throw new IllegalStateException(
                    "Payment does not match active refund reservation");
        }

        if (reservation.originalPaymentStatus()
                    != PaymentStatus.CAPTURED

                && reservation.originalPaymentStatus()
                    != PaymentStatus.PARTIALLY_REFUNDED) {

            throw new IllegalStateException(
                    "Invalid original refund payment status");
        }

        if (reservation.commissionReversalAmount()
                .add(reservation.providerEarningsReversalAmount())
                .compareTo(operation.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Refund reservation financial amounts are inconsistent");
        }

        // Unallocated earnings require separate lifecycle verification.
        if (reservation.unallocatedProviderReversalAmount()
                .signum() != 0) {

            throw new IllegalStateException(
                    "Unrecognized earnings refund is not supported");
        }

        // Ordinary refunds must never consume dispute-held earnings.
        // Dispute refunds require the shared settlement integrity checks.
        if (operation.getDisputeId() == null
                && reservation.heldAllocation().signum() != 0) {

            throw new IllegalStateException(
                    "Ordinary refund cannot consume dispute-held earnings");
        }
    }

    /**
     * Prevents conflicting terminal outcomes from overwriting
     * a previously finalized refund operation.
     */
    private void validateTerminalRetry(
            RefundOperation operation,
            RefundResult result) {

        boolean matchingSuccess =
                operation.getStatus() == RefundStatus.SUCCEEDED

                && result.status()
                    == com.gamagerecruiters.marketplace.modules.payment
                            .gateway.PaymentRefundGateway.RefundStatus.SUCCEEDED;

        boolean matchingFailure =
                operation.getStatus() == RefundStatus.FAILED

                && (result.status()
                    == com.gamagerecruiters.marketplace.modules.payment
                            .gateway.PaymentRefundGateway.RefundStatus.FAILED

                || result.status()
                    == com.gamagerecruiters.marketplace.modules.payment
                            .gateway.PaymentRefundGateway.RefundStatus.CANCELED);

        if ((!matchingSuccess && !matchingFailure)

                || !Objects.equals(
                        operation.getGatewayRefundReference(),
                        result.gatewayRefundReference())) {

            throw new IllegalStateException(
                    "Gateway result conflicts with finalized refund");
        }
    }

    private RefundOperation findOperation(String operationId) {

        return refundRepository.findById(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Refund operation not found"));
    }

    private static boolean isTerminal(RefundOperation operation) {

        return operation.getStatus() == RefundStatus.SUCCEEDED
                || operation.getStatus() == RefundStatus.FAILED;
    }

    private static void requireProcessingState(
            RefundOperation operation) {

        if (operation.getStatus() != RefundStatus.PROCESSING

                && operation.getStatus()
                    != RefundStatus.REQUIRES_RECONCILIATION) {

            throw new IllegalStateException(
                    "Refund operation is not eligible for finalization");
        }
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