package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Comparator;
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
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;

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

        return resolveRecognitionAtomically(payment, entry, true);
    }

    /**
     * Recovers existing recognition without permitting a new event.
     */
    public EarningsLedgerEntry recoverExistingRecognition(
            Payment payment,
            EarningsLedgerEntry entry) {

        return resolveRecognitionAtomically(payment, entry, false);
    }

    private EarningsLedgerEntry resolveRecognitionAtomically(
            Payment payment,
            EarningsLedgerEntry entry,
            boolean allowInitialRecognition) {

        Objects.requireNonNull(payment, "Payment is required");
        Objects.requireNonNull(entry, "Earnings event is required");

        validateRecognitionRequest(payment, entry);

        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {

                    requireActiveMongoTransaction();

                    // Preserve evidence of any previous recognition.
                    boolean previouslyMarked = false;

                    if (payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
                        previouslyMarked = mongoTemplate.exists(
                                Query.query(
                                        Criteria.where("_id").is(payment.getId())
                                                .and("earningsRecognitionWriteToken")
                                                .ne(null)),
                                Payment.class);
                    }

                    // Serialize recognition with refund reservations.
                    claimPaymentSnapshot(payment);

                    // Preserve existing event-key idempotency.
                    EarningsLedgerEntry existing =
                            payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                                    ? findRefundAdjustedCommittedEvent(payment, entry)
                                    : earningsLedgerService
                                            .findMatchingCommittedEvent(entry)
                                            .orElse(null);

                    if (existing != null) {
                        return existing;
                    }

                    if (!allowInitialRecognition) {
                        throw new IllegalStateException(
                                "No previously committed pending earnings event");
                    }

                    if (payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {

                        if (previouslyMarked) {
                            throw new IllegalStateException(
                                    "Previous earnings recognition marker has no matching event");
                        }

                        verifyPreRecognitionRefundHistory(payment, entry);
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

        if (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {

            throw new IllegalStateException(
                    "Initial earnings recognition requires a captured or partially refunded payment");
        }

        if (payment.getRefundReservationToken() != null
                || payment.getCheckoutReservationToken() != null) {

            throw new IllegalStateException(
                    "Payment has an active financial reservation");
        }

        BigDecimal refunded = payment.getRefundedAmount() == null
                ? BigDecimal.ZERO
                : payment.getRefundedAmount();

        if (refunded.signum() < 0
                || (payment.getStatus() == PaymentStatus.CAPTURED
                    && refunded.signum() != 0)
                || (payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                    && refunded.signum() <= 0)) {

            throw new IllegalStateException(
                    "Payment status and refunded amount are inconsistent");
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

        if (refunded.compareTo(payment.getAmount()) >= 0) {
            throw new IllegalStateException(
                    "Fully refunded payments cannot generate earnings");
        }

        BigDecimal expectedEarnings = new RefundCommissionCalculator()
                .calculateRemainingProviderEarnings(
                        payment.getAmount(),
                        payment.getCommissionAmount(),
                        refunded);

        if (expectedEarnings.signum() <= 0
                || expectedEarnings.compareTo(entry.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Earnings amount does not match the payment snapshot");
        }
    }

    /**
     * Resolves an existing initial earnings event after subsequent refunds.
     *
     * The original event remains immutable. Its financial identity and
     * refund-adjusted entitlement must match the persisted payment.
     */
    private EarningsLedgerEntry findRefundAdjustedCommittedEvent(
            Payment payment,
            EarningsLedgerEntry requested) {

        requireActiveMongoTransaction();

        EarningsLedgerEntry existing = mongoTemplate.findOne(
                Query.query(Criteria.where("eventKey")
                        .is(requested.getEventKey())),
                EarningsLedgerEntry.class);

        if (existing == null) {
            return null;
        }

        existing.validate();

        if (existing.getId() == null
                || existing.getSequenceNumber() == null
                || existing.getSequenceNumber() <= 0
                || !Objects.equals(
                        existing.getEventKey(), requested.getEventKey())
                || !Objects.equals(
                        existing.getPaymentId(), requested.getPaymentId())
                || !Objects.equals(
                        existing.getBookingId(), requested.getBookingId())
                || !Objects.equals(
                        existing.getProviderId(), requested.getProviderId())
                || !Objects.equals(
                        existing.getCurrency(), requested.getCurrency())
                || existing.getEventType()
                        != EarningsLedgerEventType.EARNING_PENDING) {

            throw new IllegalStateException(
                    "Existing recognition event has conflicting financial identity");
        }

        List<RefundOperation> operations = mongoTemplate.find(
                Query.query(Criteria.where("paymentId")
                        .is(payment.getId())),
                RefundOperation.class);

        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal recognizedReversals = BigDecimal.ZERO;
        BigDecimal unrecognizedRefunds = BigDecimal.ZERO;
        boolean recognizedRefundSeen = false;

        RefundCommissionCalculator calculator =
                new RefundCommissionCalculator();

        for (RefundOperation operation : operations) {

            RefundReservation reservation = operation.getReservation();

            if (reservation == null
                    || !Objects.equals(
                            payment.getId(), operation.getPaymentId())
                    || !Objects.equals(
                            payment.getBookingId(), operation.getBookingId())
                    || !Objects.equals(
                            payment.getCurrency(), operation.getCurrency())
                    || !Objects.equals(
                            payment.getGatewayPaymentIntentId(),
                            operation.getGatewayPaymentReference())
                    || !Objects.equals(
                            requested.getProviderId(), reservation.providerId())
                    || operation.getAmount() == null
                    || operation.getAmount().signum() <= 0) {

                throw new IllegalStateException(
                        "Invalid historical refund identity");
            }

            if (operation.getStatus()
                        != RefundOperation.RefundStatus.SUCCEEDED
                    && operation.getStatus()
                        != RefundOperation.RefundStatus.FAILED) {

                throw new IllegalStateException(
                        "Unresolved refund prevents recognition recovery");
            }
        }

        // Validate terminal ledger evidence for every historical refund.
        for (RefundOperation operation : operations) {
            verifyHistoricalRefundLedger(
                    operation, operation.getReservation(), existing);
        }

        List<RefundOperation> successful = operations.stream()
                .filter(operation -> operation.getStatus()
                        == RefundOperation.RefundStatus.SUCCEEDED)
                .sorted(Comparator.comparing(operation ->
                        operation.getReservation()
                                .previouslyRefundedAmount()))
                .toList();

        for (RefundOperation operation : successful) {

            RefundReservation reservation = operation.getReservation();

            if (reservation.previouslyRefundedAmount()
                        .compareTo(cumulative) != 0
                    || operation.getGatewayRefundReference() == null
                    || operation.getGatewayRefundReference().isBlank()
                    || operation.getCompletedAt() == null) {

                throw new IllegalStateException(
                        "Inconsistent historical refund sequence");
            }

            RefundCommissionCalculator.RefundAmounts expected =
                    calculator.calculate(
                            payment.getAmount(),
                            payment.getCommissionAmount(),
                            cumulative,
                            operation.getAmount());

            if (expected.commissionReversalAmount().compareTo(
                        reservation.commissionReversalAmount()) != 0
                    || expected.providerEarningsReversalAmount().compareTo(
                        reservation.providerEarningsReversalAmount()) != 0) {

                throw new IllegalStateException(
                        "Historical refund calculation mismatch");
            }

            if (Boolean.TRUE.equals(
                    reservation.verifiedUnrecognizedEarnings())) {

                if (recognizedRefundSeen
                        || reservation.totalAllocatedEarnings().signum() != 0
                        || operation.getDisputeId() != null) {

                    throw new IllegalStateException(
                            "Invalid pre-recognition refund sequence");
                }

                unrecognizedRefunds =
                        expected.cumulativeRefundedAmount();

            } else {

                recognizedRefundSeen = true;

                if (reservation.totalAllocatedEarnings().compareTo(
                        expected.providerEarningsReversalAmount()) != 0) {

                    throw new IllegalStateException(
                            "Historical earnings allocation mismatch");
                }

                recognizedReversals = recognizedReversals.add(
                        expected.providerEarningsReversalAmount());
            }

            cumulative = expected.cumulativeRefundedAmount();
        }

        BigDecimal originalRecognitionAmount =
                calculator.calculateRemainingProviderEarnings(
                        payment.getAmount(),
                        payment.getCommissionAmount(),
                        unrecognizedRefunds);

        if (cumulative.compareTo(payment.getRefundedAmount()) != 0
                || existing.getAmount().compareTo(
                        originalRecognitionAmount) != 0
                || existing.getAmount()
                        .subtract(recognizedReversals)
                        .compareTo(requested.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Existing recognition amount conflicts with refund history");
        }

        return existing;
    }

    /**
     * Verifies reservation and terminal event ordering for historical
     * refunds without modifying committed financial records.
     */
    private void verifyHistoricalRefundLedger(
            RefundOperation operation,
            RefundReservation reservation,
            EarningsLedgerEntry initialEvent) {

        if (operation.getId() == null
                || operation.getId().isBlank()) {

            throw new IllegalStateException(
                    "Historical refund operation ID is required");
        }

        String id = operation.getId();

        String[] sources = {"PENDING", "AVAILABLE", "HELD"};

        BigDecimal[] amounts = {
                reservation.pendingAllocation(),
                reservation.availableAllocation(),
                reservation.heldAllocation()
        };

        EarningsLedgerEventType[] reserveTypes = {
                EarningsLedgerEventType.REFUND_RESERVE_FROM_PENDING,
                EarningsLedgerEventType.REFUND_RESERVE_FROM_AVAILABLE,
                EarningsLedgerEventType.REFUND_RESERVE_FROM_HELD
        };

        EarningsLedgerEventType[] releaseTypes = {
                EarningsLedgerEventType.REFUND_RELEASE_TO_PENDING,
                EarningsLedgerEventType.REFUND_RELEASE_TO_AVAILABLE,
                EarningsLedgerEventType.REFUND_RELEASE_TO_HELD
        };

        boolean succeeded = operation.getStatus()
                == RefundOperation.RefundStatus.SUCCEEDED;

        long lastReservationSequence =
                initialEvent.getSequenceNumber();

        for (int i = 0; i < sources.length; i++) {

            RefundReservationIntegrityValidator.validateReservationEvent(
                    mongoTemplate,
                    operation,
                    reservation,
                    sources[i],
                    reserveTypes[i],
                    amounts[i]);

            if (amounts[i].signum() > 0) {

                EarningsLedgerEntry reserved = mongoTemplate.findOne(
                        Query.query(Criteria.where("eventKey").is(
                                "REFUND_RESERVE:" + id + ":" + sources[i])),
                        EarningsLedgerEntry.class);

                if (reserved == null
                        || reserved.getSequenceNumber() == null
                        || reserved.getSequenceNumber()
                            <= initialEvent.getSequenceNumber()) {

                    throw new IllegalStateException(
                            "Historical refund reservation sequence mismatch");
                }

                lastReservationSequence = Math.max(
                        lastReservationSequence,
                        reserved.getSequenceNumber());
            }
        }

        for (int i = 0; i < sources.length; i++) {

            String key = "REFUND_RELEASE_" + sources[i] + ":" + id;

            verifyHistoricalTerminalEvent(
                    operation,
                    reservation,
                    key,
                    releaseTypes[i],
                    succeeded ? BigDecimal.ZERO : amounts[i],
                    lastReservationSequence);
        }

        verifyHistoricalTerminalEvent(
                operation,
                reservation,
                "REFUND_SETTLE:" + id,
                EarningsLedgerEventType.REFUND_SETTLE,
                succeeded
                        ? reservation.totalAllocatedEarnings()
                        : BigDecimal.ZERO,
                lastReservationSequence);
    }

    /**
     * Verifies the identity, amount and ordering of a terminal
     * refund event. Zero amounts require the event to be absent.
     */
    private void verifyHistoricalTerminalEvent(
            RefundOperation operation,
            RefundReservation reservation,
            String key,
            EarningsLedgerEventType expectedType,
            BigDecimal expectedAmount,
            long previousSequence) {

        EarningsLedgerEntry event = mongoTemplate.findOne(
                Query.query(Criteria.where("eventKey").is(key)),
                EarningsLedgerEntry.class);

        if (expectedAmount.signum() == 0) {

            if (event != null) {
                throw new IllegalStateException(
                        "Unexpected historical terminal refund event");
            }

            return;
        }

        if (event == null) {
            throw new IllegalStateException(
                    "Historical terminal refund event is missing");
        }

        event.validate();

        if (event.getId() == null
                || event.getSequenceNumber() == null
                || event.getSequenceNumber() <= previousSequence
                || !Objects.equals(key, event.getEventKey())
                || !Objects.equals(
                        operation.getPaymentId(), event.getPaymentId())
                || !Objects.equals(
                        operation.getBookingId(), event.getBookingId())
                || !Objects.equals(
                        reservation.providerId(), event.getProviderId())
                || !Objects.equals(
                        operation.getCurrency(), event.getCurrency())
                || event.getEventType() != expectedType
                || event.getAmount().compareTo(expectedAmount) != 0) {

            throw new IllegalStateException(
                    "Historical terminal refund event is inconsistent");
        }
    }

    /**
     * Verifies successful pre-recognition refunds against the original
     * financial snapshot before creating an initial earnings event.
     *
     * Must be called inside the recognition transaction after the
     * conditional payment write has succeeded.
     */
    private void verifyPreRecognitionRefundHistory(
            Payment payment,
            EarningsLedgerEntry entry) {

        requireActiveMongoTransaction();

        // Reuse the same historical financial validation used
        // during refund reservation, execution and finalization.
        PreRecognitionRefundHistoryValidator.validate(
                mongoTemplate,
                payment,
                entry.getProviderId(),
                null);

        if (payment.getRefundedAmount() == null
                || payment.getRefundedAmount().signum() <= 0) {

            throw new IllegalStateException(
                    "Partial recognition requires verified refund history");
        }

        // Initial recognition must not overwrite existing ledger history.
        Query ledgerHistory = Query.query(
                new Criteria().orOperator(
                        Criteria.where("paymentId").is(payment.getId()),
                        Criteria.where("bookingId").is(payment.getBookingId()),
                        Criteria.where("eventKey")
                                .is("earning-pending:" + payment.getId())));

        if (mongoTemplate.exists(
                ledgerHistory, EarningsLedgerEntry.class)) {

            throw new IllegalStateException(
                    "Existing ledger history prevents initial recognition");
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