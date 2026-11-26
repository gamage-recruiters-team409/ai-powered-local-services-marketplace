package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import java.util.Objects;
import java.util.List;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;

/**
 * Verifies the financial integrity of persisted refund reservations.
 *
 * <p>Reservation metadata alone does not establish that the
 * corresponding provider earnings were actually reserved.
 * The committed ledger events must match the exact refund operation.</p>
 *
 * <p>This validator performs read-only checks and never creates,
 * modifies or releases financial reservations.</p>
 */
final class RefundReservationIntegrityValidator {

    private RefundReservationIntegrityValidator() {
    }

    /**
     * Validates commission calculations and committed reservation
     * events before gateway execution or refund finalization.
     */
    static void validate(
            MongoTemplate mongoTemplate,
            Payment payment,
            RefundOperation operation,
            RefundReservation reservation) {

        Objects.requireNonNull(mongoTemplate);
        Objects.requireNonNull(payment);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(reservation);

        if (operation.getId() == null
                || operation.getId().isBlank()
                || !Objects.equals(
                        payment.getId(), operation.getPaymentId())
                || !Objects.equals(
                        payment.getBookingId(), operation.getBookingId())) {

            throw new IllegalStateException(
                    "Refund reservation identity mismatch");
        }

        if (operation.getDisputeId() == null) {

            if (reservation.heldAllocation().signum() != 0) {
                throw new IllegalStateException(
                        "Ordinary refund cannot consume dispute-held earnings");
            }

        } else {
            validateDisputeHold(
                    mongoTemplate, operation, reservation);
        }

        // Recalculate financial amounts from the original payment.
        RefundCommissionCalculator.RefundAmounts expected =
                new RefundCommissionCalculator().calculate(
                        payment.getAmount(),
                        payment.getCommissionAmount(),
                        reservation.previouslyRefundedAmount(),
                        operation.getAmount());

        if (expected.commissionReversalAmount().compareTo(
                    reservation.commissionReversalAmount()) != 0

                || expected.providerEarningsReversalAmount().compareTo(
                    reservation.providerEarningsReversalAmount()) != 0) {

            throw new IllegalStateException(
                    "Refund reservation financial calculation mismatch");
        }

        if (Boolean.TRUE.equals(
                reservation.verifiedUnrecognizedEarnings())) {

            validateUnrecognizedEarnings(
                    mongoTemplate, payment, operation, reservation);

        } else if (reservation.totalAllocatedEarnings().compareTo(
                expected.providerEarningsReversalAmount()) != 0) {

            throw new IllegalStateException(
                    "Refund reservation financial allocation mismatch");
        }

        validateReservationEvent(
                mongoTemplate,
                operation,
                reservation,
                "PENDING",
                EarningsLedgerEventType.REFUND_RESERVE_FROM_PENDING,
                reservation.pendingAllocation());

        validateReservationEvent(
                mongoTemplate,
                operation,
                reservation,
                "AVAILABLE",
                EarningsLedgerEventType.REFUND_RESERVE_FROM_AVAILABLE,
                reservation.availableAllocation());

        // Ordinary refunds must not contain held reservation events.
        validateReservationEvent(
                mongoTemplate,
                operation,
                reservation,
                "HELD",
                EarningsLedgerEventType.REFUND_RESERVE_FROM_HELD,
                reservation.heldAllocation());

        // Reject reservations that have already been consumed.
        requireAbsentEvent(
                mongoTemplate,
                "REFUND_SETTLE:" + operation.getId());

        requireAbsentEvent(
                mongoTemplate,
                "REFUND_RELEASE_PENDING:" + operation.getId());

        requireAbsentEvent(
                mongoTemplate,
                "REFUND_RELEASE_AVAILABLE:" + operation.getId());

        requireAbsentEvent(
                mongoTemplate,
                "REFUND_RELEASE_HELD:" + operation.getId());
    }

    /**
     * Revalidates unrecognized earnings before gateway execution
     * and refund finalization.
     *
     * The persisted marker alone is insufficient. The payment must
     * still own the refund reservation and have no recognized
     * earnings history.
     */
    private static void validateUnrecognizedEarnings(
            MongoTemplate mongoTemplate,
            Payment payment,
            RefundOperation operation,
            RefundReservation reservation) {

        if (operation.getDisputeId() != null
                || reservation.totalAllocatedEarnings().signum() != 0
                || payment.getStatus() != PaymentStatus.REFUND_PENDING
                || !Objects.equals(
                        payment.getRefundReservationToken(),
                        operation.getIdempotencyKey())
                || payment.getRefundedAmount() == null
                || payment.getRefundedAmount().compareTo(
                        reservation.previouslyRefundedAmount()) != 0
                || !Objects.equals(
                        payment.getCurrency(), operation.getCurrency())
                || !Objects.equals(
                        payment.getGatewayPaymentIntentId(),
                        operation.getGatewayPaymentReference())) {

            throw new IllegalStateException(
                    "Invalid unrecognized earnings refund reservation");
        }

        Query reservedPayment = Query.query(
                Criteria.where("_id").is(payment.getId())
                        .and("status").is(PaymentStatus.REFUND_PENDING)
                        .and("refundReservationToken")
                            .is(operation.getIdempotencyKey())
                        .and("checkoutReservationToken").is(null)
                        .and("earningsRecognitionWriteToken").is(null));

        if (!mongoTemplate.exists(reservedPayment, Payment.class)) {

            throw new IllegalStateException(
                    "Unrecognized earnings reservation is no longer valid");
        }

        PreRecognitionRefundHistoryValidator.validate(
                mongoTemplate,
                payment,
                reservation.providerId(),
                operation.getId());

        // Reject any payment-specific or booking-specific ledger
        // history, including an initial event with inconsistent identity.
        Query history = Query.query(
                new Criteria().orOperator(
                        Criteria.where("paymentId")
                                .is(payment.getId()),
                        Criteria.where("bookingId")
                                .is(payment.getBookingId()),
                        Criteria.where("eventKey")
                                .is("earning-pending:" + payment.getId())));

        if (mongoTemplate.exists(history, EarningsLedgerEntry.class)) {

            throw new IllegalStateException(
                    "Unrecognized refund contains existing earnings history");
        }
    }

    /**
     * Confirms that a committed reservation event matches the
     * expected operation identity and financial allocation.
     */
    static void validateReservationEvent(
            MongoTemplate mongoTemplate,
            RefundOperation operation,
            RefundReservation reservation,
            String source,
            EarningsLedgerEventType expectedType,
            BigDecimal expectedAmount) {

        String eventKey =
                "REFUND_RESERVE:" + operation.getId() + ":" + source;

        Query query = Query.query(
                Criteria.where("eventKey").is(eventKey));

        if (expectedAmount.signum() == 0) {

            requireAbsentEvent(mongoTemplate, eventKey);
            return;
        }

        EarningsLedgerEntry entry = mongoTemplate.findOne(
                query, EarningsLedgerEntry.class);

        if (entry == null) {

            throw new IllegalStateException(
                    "Required refund reservation ledger event is missing");
        }

        entry.validate();

        if (entry.getId() == null

                || entry.getSequenceNumber() == null

                || entry.getSequenceNumber() <= 0

                || !eventKey.equals(entry.getEventKey())

                || !Objects.equals(
                        operation.getPaymentId(),
                        entry.getPaymentId())

                || !Objects.equals(
                        operation.getBookingId(),
                        entry.getBookingId())

                || !Objects.equals(
                        reservation.providerId(),
                        entry.getProviderId())

                || !Objects.equals(
                        operation.getCurrency(),
                        entry.getCurrency())

                || entry.getEventType() != expectedType

                || expectedAmount.compareTo(entry.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Refund reservation ledger event mismatch");
        }
    }

    /**
     * Rejects unexpected reservation or previously consumed events.
     */
    private static void requireAbsentEvent(
            MongoTemplate mongoTemplate,
            String eventKey) {

        Query query = Query.query(
                Criteria.where("eventKey").is(eventKey));

        if (mongoTemplate.exists(
                query, EarningsLedgerEntry.class)) {

            throw new IllegalStateException(
                    "Unexpected or previously consumed refund ledger event");
        }
    }

    /**
     * Verifies that a dispute refund owns the corresponding
     * persisted settlement hold and uses only held earnings.
     */
    private static void validateDisputeHold(
            MongoTemplate mongoTemplate,
            RefundOperation operation,
            RefundReservation reservation) {

        String disputeId = operation.getDisputeId();

        if (disputeId.isBlank()
                || !("dispute-" + disputeId).equals(
                        operation.getIdempotencyKey())
                || reservation.pendingAllocation().signum() != 0
                || reservation.availableAllocation().signum() != 0
                || reservation.heldAllocation().compareTo(
                        reservation.providerEarningsReversalAmount()) != 0) {

            throw new IllegalStateException(
                    "Invalid dispute refund reservation");
        }

        Query query = Query.query(
                Criteria.where("disputeId").is(disputeId));

        DisputePayoutHold hold = mongoTemplate.findOne(
                query, DisputePayoutHold.class);

        if (hold == null
                || hold.getStatus() != DisputePayoutHold.HoldStatus.SETTLING
                || !Objects.equals(
                        operation.getPaymentId(), hold.getPaymentId())
                || !Objects.equals(
                        operation.getBookingId(), hold.getBookingId())
                || !Objects.equals(
                        reservation.providerId(), hold.getProviderId())
                || !Objects.equals(
                        operation.getCurrency(), hold.getCurrency())
                || hold.getAmount() == null
                || hold.getAmount().signum() <= 0
                || hold.getAmount().compareTo(
                        reservation.heldAllocation()) < 0) {

            throw new IllegalStateException(
                    "Dispute refund does not own a valid settlement hold");
        }

        requireAbsentEvent(
                mongoTemplate,
                "DISPUTE_RELEASE:" + disputeId);

        requireAbsentEvent(
                mongoTemplate,
                "DISPUTE_RELEASE:" + disputeId + ":AVAILABLE");

        validateOriginalHoldEvents(mongoTemplate, hold);

        requireAbsentEvent(
                mongoTemplate,
                "DISPUTE_REFUND_REMAINDER_PENDING:" + operation.getId());

        requireAbsentEvent(
                mongoTemplate,
                "DISPUTE_REFUND_REMAINDER_AVAILABLE:" + operation.getId());

        validateDisputeReservedBalances(
                mongoTemplate, operation, reservation, hold);
    }


    /**
     * Verifies the original booking-specific hold events before
     * executing or finalizing a dispute refund.
     */

    /**
     * Verifies the booking-specific financial balances before
     * executing or finalizing a reserved dispute refund.
     */
    private static void validateDisputeReservedBalances(
            MongoTemplate mongoTemplate,
            RefundOperation operation,
            RefundReservation reservation,
            DisputePayoutHold hold) {

        List<EarningsLedgerEntry> history = mongoTemplate.find(
                Query.query(Criteria.where("bookingId")
                        .is(operation.getBookingId())),
                EarningsLedgerEntry.class);

        if (history.isEmpty()) {
            throw new IllegalStateException(
                    "Dispute refund earnings history is missing");
        }

        for (EarningsLedgerEntry entry : history) {

            entry.validate();

            if (entry.getId() == null
                    || entry.getSequenceNumber() == null
                    || entry.getSequenceNumber() <= 0
                    || !Objects.equals(
                            operation.getPaymentId(), entry.getPaymentId())
                    || !Objects.equals(
                            operation.getBookingId(), entry.getBookingId())
                    || !Objects.equals(
                            reservation.providerId(), entry.getProviderId())
                    || !Objects.equals(
                            operation.getCurrency(), entry.getCurrency())) {

                throw new IllegalStateException(
                        "Dispute refund ledger ownership or sequence mismatch");
            }
        }

        EarningsBalanceCalculator.EarningsBalance balance =
                new EarningsBalanceCalculator().calculate(history);

        BigDecimal expectedHeld = hold.getAmount()
                .subtract(reservation.heldAllocation());

        if (expectedHeld.signum() < 0
                || balance.held().compareTo(expectedHeld) != 0
                || balance.refundReserved().compareTo(
                        reservation.heldAllocation()) != 0
                || balance.payoutReserved().signum() != 0) {

            throw new IllegalStateException(
                    "Dispute refund reserved balances are inconsistent");
        }
    }

    private static void validateOriginalHoldEvents(
            MongoTemplate mongoTemplate,
            DisputePayoutHold hold) {

        String key = "DISPUTE_HOLD:" + hold.getDisputeId();

        EarningsLedgerEntry primary = mongoTemplate.findOne(
                Query.query(Criteria.where("eventKey").is(key)),
                EarningsLedgerEntry.class);

        EarningsLedgerEntry secondary = mongoTemplate.findOne(
                Query.query(Criteria.where("eventKey")
                        .is(key + ":AVAILABLE")),
                EarningsLedgerEntry.class);

        validateOriginalHoldEntry(hold, primary, key);

        BigDecimal total = primary.getAmount();

        if (secondary != null) {

            validateOriginalHoldEntry(
                    hold, secondary, key + ":AVAILABLE");

            if (primary.getEventType()
                    != EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING
                    || secondary.getEventType()
                    != EarningsLedgerEventType.EARNING_HOLD
                    || primary.getSequenceNumber()
                            >= secondary.getSequenceNumber()) {

                throw new IllegalStateException(
                        "Invalid split dispute hold events");
            }

            total = total.add(secondary.getAmount());
        }

        if (total.compareTo(hold.getAmount()) != 0) {
            throw new IllegalStateException(
                    "Original dispute hold amount mismatch");
        }
    }

    /**
     * Validates the identity, amount and sequence of a committed
     * original dispute hold event.
     */
    private static void validateOriginalHoldEntry(
            DisputePayoutHold hold,
            EarningsLedgerEntry entry,
            String expectedKey) {

        if (entry == null) {
            throw new IllegalStateException(
                    "Original dispute hold event is missing");
        }

        entry.validate();

        if (entry.getId() == null
                || entry.getSequenceNumber() == null
                || entry.getSequenceNumber() <= 0
                || !Objects.equals(expectedKey, entry.getEventKey())
                || !Objects.equals(
                        hold.getPaymentId(), entry.getPaymentId())
                || !Objects.equals(
                        hold.getBookingId(), entry.getBookingId())
                || !Objects.equals(
                        hold.getProviderId(), entry.getProviderId())
                || !Objects.equals(
                        hold.getCurrency(), entry.getCurrency())
                || entry.getAmount() == null
                || entry.getAmount().signum() <= 0
                || (entry.getEventType()
                        != EarningsLedgerEventType.EARNING_HOLD
                    && entry.getEventType()
                        != EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING)) {

            throw new IllegalStateException(
                    "Original dispute hold event is inconsistent");
        }
    }

}