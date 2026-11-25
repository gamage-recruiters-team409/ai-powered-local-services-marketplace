package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Coordinates atomic financial reservations for refund operations.
 *
 * Payment reservation, provider earnings reservation and refund
 * operation persistence must succeed within the same transaction.
 *
 * External gateway calls must occur outside this transaction.
 * This internal service does not authorize external API callers.
 */
@Service
public class RefundTransactionService {

    private final PaymentRepository paymentRepository;
    private final RefundOperationRepository refundRepository;
    private final BookingFacade bookingFacade;
    private final RefundCommissionCalculator commissionCalculator;
    private final RefundReservationService reservationService;
    private final RefundEarningsAllocationService allocationService;
    private final EarningsLedgerTransactionService ledgerTransactionService;
    private final DisputePayoutHoldRepository holdRepository;
    private final DisputePayoutHoldTransactionService holdService;
    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactionTemplate;

    public RefundTransactionService(
            PaymentRepository paymentRepository,
            RefundOperationRepository refundRepository,
            BookingFacade bookingFacade,
            RefundCommissionCalculator commissionCalculator,
            RefundReservationService reservationService,
            RefundEarningsAllocationService allocationService,
            EarningsLedgerTransactionService ledgerTransactionService,
            DisputePayoutHoldRepository holdRepository,
            DisputePayoutHoldTransactionService holdService,
            MongoTemplate mongoTemplate,
            PlatformTransactionManager transactionManager) {

        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.refundRepository = Objects.requireNonNull(refundRepository);
        this.bookingFacade = Objects.requireNonNull(bookingFacade);
        this.commissionCalculator = Objects.requireNonNull(commissionCalculator);
        this.reservationService = Objects.requireNonNull(reservationService);
        this.allocationService = Objects.requireNonNull(allocationService);
        this.ledgerTransactionService =
                Objects.requireNonNull(ledgerTransactionService);

        this.holdRepository = Objects.requireNonNull(holdRepository);
        this.holdService = Objects.requireNonNull(holdService);
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);

        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
    }

    /**
     * Reserves funds for an internally authorized refund request.
     *
     * Repeated requests with identical financial data return the
     * existing operation without creating additional reservations.
     *
     * Ordinary refunds may include independently verified unrecognized
     * earnings. Disputed bookings require the dispute settlement workflow.
     */
    public RefundOperation reserveRefund(
            String paymentId,
            String idempotencyKey,
            BigDecimal amount) {

        String normalizedPaymentId =
                requireText(paymentId, "Payment ID");

        String normalizedKey =
                requireText(idempotencyKey, "Idempotency key");

        if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2) {

            throw new IllegalArgumentException(
                    "Refund amount must be positive with at most two decimal places");
        }

        return Objects.requireNonNull(
                transactionTemplate.execute(status ->
                        reserveAtomically(
                                normalizedPaymentId,
                                normalizedKey,
                                amount, null)));
    }

    /**
     * Reserves an internally authorized dispute refund.
     *
     * Payment, hold, refund operation and ledger changes must
     * participate in the same MongoDB transaction.
     * No external gateway request is made here.
     */
    public RefundOperation reserveDisputeRefund(
            String paymentId,
            String disputeId,
            BigDecimal amount) {

        String normalizedPaymentId =
                requireText(paymentId, "Payment ID");

        String normalizedDisputeId =
                requireText(disputeId, "Dispute ID");

if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2) {
            throw new IllegalArgumentException(
                    "Invalid dispute refund amount");
        }

        String idempotencyKey = "dispute-" + normalizedDisputeId;

        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {

                    if (!TransactionSynchronizationManager
                            .isActualTransactionActive()
                            || !TransactionSynchronizationManager.hasResource(
                                    mongoTemplate.getMongoDatabaseFactory())) {

                        throw new IllegalStateException(
                                "Dispute refund requires an active MongoDB transaction");
                    }

                    return reserveAtomically(
                            normalizedPaymentId,
                            idempotencyKey,
                            amount,
                            normalizedDisputeId);
                }));
    }

    private RefundOperation reserveAtomically(
            String paymentId,
            String idempotencyKey,
            BigDecimal amount,
            String disputeId) {

        RefundOperation existing = refundRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);

        if (existing != null) {

            if (!paymentId.equals(existing.getPaymentId())
                    || existing.getAmount().compareTo(amount) != 0
                    || !Objects.equals(disputeId, existing.getDisputeId())) {

                throw new IllegalStateException(
                        "Refund idempotency key conflicts with existing financial data");
            }

            return existing;
        }

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found"));

        String bookingId = requireText(
                payment.getBookingId(), "Booking ID");

        String currency = requireText(
                payment.getCurrency(), "Payment currency");

        String customerId = requireText(
                payment.getCustomerId(), "Payment customer ID");

        String gatewayPaymentReference = requireText(
                payment.getGatewayPaymentIntentId(),
                "Verified gateway payment reference");

        if (payment.getPaidAt() == null) {
            throw new IllegalStateException(
                    "Refund requires verified payment capture");
        }

        BookingParticipants participants =
                bookingFacade.getBookingParticipants(bookingId);

        if (participants == null
                || !bookingId.equals(participants.bookingId())
                || !customerId.equals(participants.customerId())) {

            throw new IllegalStateException(
                    "Payment does not match trusted booking participants");
        }

        String providerId = requireText(
                participants.providerId(), "Booking provider ID");

        RefundCommissionCalculator.RefundAmounts refundAmounts =
                commissionCalculator.calculate(
                        payment.getAmount(),
                        payment.getCommissionAmount(),
                        payment.getRefundedAmount(),
                        amount);

        // Claim the payment before writing any refund ledger events.
        if (!reservationService.tryReserve(payment, idempotencyKey)) {
            throw new IllegalStateException(
                    "Payment changed or already has an active refund reservation");
        }

        RefundReservation reservation;

        if (disputeId == null) {

            // Disputed bookings require authorized dispute settlement.
            if (participants.status() == BookingStatus.DISPUTED) {
                throw new IllegalStateException(
                        "Disputed booking requires dispute refund settlement");
            }

            // Verify recognition state after acquiring the payment write.
            boolean verifiedUnrecognizedEarnings =
                    verifyUnrecognizedEarnings(payment, idempotencyKey, providerId);

            reservation = allocationService.calculateAllocation(
                    payment,
                    providerId,
                    refundAmounts,
                    verifiedUnrecognizedEarnings);

        } else {

            if (participants.status() != BookingStatus.DISPUTED) {
                throw new IllegalStateException(
                        "Dispute refund requires a disputed booking");
            }

            DisputePayoutHold hold = holdRepository
                    .findByDisputeId(disputeId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Dispute payout hold not found"));

            if (!providerId.equals(hold.getProviderId())) {
                throw new IllegalStateException(
                        "Dispute hold provider ownership mismatch");
            }

            // Validate booking-specific held earnings before claiming.
            reservation = allocationService.calculateDisputeAllocation(
                    payment,
                    hold,
                    refundAmounts);

            // Claim the hold inside the same refund transaction.
            holdService.claimHoldForRefund(hold);
        }

        RefundOperation operation = new RefundOperation(
                idempotencyKey,
                paymentId,
                bookingId,
                disputeId,
                amount,
                currency);

        operation.assignReservation(reservation);
        operation.setGatewayPaymentReference(
                gatewayPaymentReference);

        RefundOperation persisted =
                refundRepository.insert(operation);

        appendReservationEvent(
                persisted,
                reservation,
                EarningsLedgerEventType.REFUND_RESERVE_FROM_PENDING,
                reservation.pendingAllocation(),
                "PENDING");

        appendReservationEvent(
                persisted,
                reservation,
                EarningsLedgerEventType.REFUND_RESERVE_FROM_AVAILABLE,
                reservation.availableAllocation(),
                "AVAILABLE");

        appendReservationEvent(
                persisted,
                reservation,
                EarningsLedgerEventType.REFUND_RESERVE_FROM_HELD,
                reservation.heldAllocation(),
                "HELD");

        return persisted;
    }

    /**
     * Establishes whether provider earnings remain unrecognized.
     *
     * Must execute after the payment reservation is acquired inside
     * the same MongoDB transaction. Recognition and refund reservation
     * both conditionally write to the payment document.
     */
    private boolean verifyUnrecognizedEarnings(
            Payment payment,
            String reservationToken,
            String providerId) {

        if (!TransactionSynchronizationManager
                    .isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(
                        mongoTemplate.getMongoDatabaseFactory())) {

            throw new IllegalStateException(
                    "Earnings verification requires an active MongoDB transaction");
        }

        Query paymentHistory = Query.query(
                Criteria.where("paymentId").is(payment.getId()));

        // Existing earnings must follow the normal allocation workflow.
        if (mongoTemplate.exists(
                paymentHistory, EarningsLedgerEntry.class)) {
            return false;
        }

        Query bookingHistory = Query.query(
                Criteria.where("bookingId").is(payment.getBookingId()));

        if (mongoTemplate.exists(
                bookingHistory, EarningsLedgerEntry.class)) {

            throw new IllegalStateException(
                    "Booking contains inconsistent earnings history");
        }

        Query initialEvent = Query.query(
                Criteria.where("eventKey")
                        .is("earning-pending:" + payment.getId()));

        if (mongoTemplate.exists(
                initialEvent, EarningsLedgerEntry.class)) {

            throw new IllegalStateException(
                    "Initial earnings event has inconsistent identity");
        }

        // Verify the current persisted reservation and absence of
        // any committed transactional recognition marker.
        Query reservedPayment = Query.query(
                Criteria.where("_id").is(payment.getId())
                        .and("status").is(PaymentStatus.REFUND_PENDING)
                        .and("refundReservationToken").is(reservationToken)
                        .and("checkoutReservationToken").is(null)
                        .and("earningsRecognitionWriteToken").is(null));

        if (!mongoTemplate.exists(reservedPayment, Payment.class)) {

            throw new IllegalStateException(
                    "Unrecognized earnings cannot be safely established");
        }

        PreRecognitionRefundHistoryValidator.validate(
                mongoTemplate, payment, providerId, null);

        if ((payment.getStatus() == PaymentStatus.CAPTURED
                    && payment.getRefundedAmount().signum() != 0)
                || (payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                    && payment.getRefundedAmount().signum() <= 0)) {

            throw new IllegalStateException(
                    "Refund history does not match payment status");
        }

        return true;
    }

    /**
     * Appends a reservation event using the refund operation identity.
     *
     * Transaction failures propagate to the outer transaction.
     * Individual ledger writes are not retried independently.
     */
    private void appendReservationEvent(
            RefundOperation operation,
            RefundReservation reservation,
            EarningsLedgerEventType eventType,
            BigDecimal amount,
            String source) {

        if (amount.signum() == 0) {
            return;
        }

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(
                "REFUND_RESERVE:" + operation.getId() + ":" + source);

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
     * Validates required identifiers and references.
     */
    private static String requireText(
            String value, String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required");
        }

        return value.trim();
    }
}