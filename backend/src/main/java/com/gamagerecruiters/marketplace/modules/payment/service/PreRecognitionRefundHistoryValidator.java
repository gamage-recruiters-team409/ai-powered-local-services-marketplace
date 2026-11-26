package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;

/**
 * Verifies historical refunds completed before earnings recognition.
 *
 * This validator is read-only and never modifies financial records.
 * The active refund operation, when present, is excluded from history.
 */
final class PreRecognitionRefundHistoryValidator {

    private PreRecognitionRefundHistoryValidator() {
    }

    static void validate(
            MongoTemplate mongoTemplate,
            Payment payment,
            String providerId,
            String activeOperationId) {

        List<RefundOperation> operations = mongoTemplate.find(
                Query.query(Criteria.where("paymentId")
                        .is(payment.getId())),
                RefundOperation.class);

        RefundCommissionCalculator calculator =
                new RefundCommissionCalculator();

        Set<String> gatewayReferences = new HashSet<>();

        List<RefundOperation> history = operations.stream()
                .filter(operation -> activeOperationId == null
                        || !activeOperationId.equals(operation.getId()))
                .toList();

        for (RefundOperation operation : history) {

            RefundReservation reservation = operation.getReservation();

            if (operation.getId() == null
                    || operation.getId().isBlank()
                    || reservation == null
                    || operation.getDisputeId() != null
                    || !Objects.equals(payment.getId(),
                            operation.getPaymentId())
                    || !Objects.equals(payment.getBookingId(),
                            operation.getBookingId())
                    || !Objects.equals(payment.getCurrency(),
                            operation.getCurrency())
                    || !Objects.equals(payment.getGatewayPaymentIntentId(),
                            operation.getGatewayPaymentReference())
                    || !Objects.equals(providerId, reservation.providerId())
                    || !Boolean.TRUE.equals(
                            reservation.verifiedUnrecognizedEarnings())
                    || reservation.totalAllocatedEarnings().signum() != 0
                    || operation.getAmount() == null
                    || operation.getAmount().signum() <= 0) {

                throw new IllegalStateException(
                        "Invalid historical pre-recognition refund");
            }

            if (operation.getStatus()
                        != RefundOperation.RefundStatus.SUCCEEDED
                    && operation.getStatus()
                        != RefundOperation.RefundStatus.FAILED) {

                throw new IllegalStateException(
                        "Unresolved historical refund operation");
            }

            BigDecimal previous = reservation.previouslyRefundedAmount();

            if (payment.getRefundedAmount() == null
                    || previous.compareTo(payment.getRefundedAmount()) > 0
                    || (previous.signum() == 0
                        && reservation.originalPaymentStatus()
                            != PaymentStatus.CAPTURED)
                    || (previous.signum() > 0
                        && reservation.originalPaymentStatus()
                            != PaymentStatus.PARTIALLY_REFUNDED)) {

                throw new IllegalStateException(
                        "Historical refund payment state mismatch");
            }

            RefundCommissionCalculator.RefundAmounts expected =
                    calculator.calculate(
                            payment.getAmount(),
                            payment.getCommissionAmount(),
                            previous,
                            operation.getAmount());

            if (expected.commissionReversalAmount().compareTo(
                        reservation.commissionReversalAmount()) != 0
                    || expected.providerEarningsReversalAmount().compareTo(
                        reservation.providerEarningsReversalAmount()) != 0) {

                throw new IllegalStateException(
                        "Historical refund financial calculation mismatch");
            }

            if (operation.getStatus()
                    == RefundOperation.RefundStatus.SUCCEEDED) {

                String reference = operation.getGatewayRefundReference();

                if (reference == null
                        || reference.isBlank()
                        || operation.getCompletedAt() == null
                        || !gatewayReferences.add(reference)) {

                    throw new IllegalStateException(
                            "Invalid historical gateway refund evidence");
                }
            }

            // Unrecognized earnings must not have generated refund events.
            String id = operation.getId();

            List<String> eventKeys = List.of(
                    "REFUND_RESERVE:" + id + ":PENDING",
                    "REFUND_RESERVE:" + id + ":AVAILABLE",
                    "REFUND_RESERVE:" + id + ":HELD",
                    "REFUND_SETTLE:" + id,
                    "REFUND_RELEASE_PENDING:" + id,
                    "REFUND_RELEASE_AVAILABLE:" + id,
                    "REFUND_RELEASE_HELD:" + id);

            if (mongoTemplate.exists(
                    Query.query(Criteria.where("eventKey").in(eventKeys)),
                    EarningsLedgerEntry.class)) {

                throw new IllegalStateException(
                        "Unexpected historical refund ledger events");
            }
        }

        List<RefundOperation> successful = history.stream()
                .filter(operation -> operation.getStatus()
                        == RefundOperation.RefundStatus.SUCCEEDED)
                .sorted(Comparator.comparing(operation ->
                        operation.getReservation().previouslyRefundedAmount()))
                .toList();

        BigDecimal cumulative = BigDecimal.ZERO;

        for (RefundOperation operation : successful) {

            if (operation.getReservation().previouslyRefundedAmount()
                    .compareTo(cumulative) != 0) {

                throw new IllegalStateException(
                        "Historical cumulative refund sequence mismatch");
            }

            cumulative = cumulative.add(operation.getAmount());
        }

        if (payment.getRefundedAmount() == null
                || cumulative.compareTo(payment.getRefundedAmount()) != 0
                || cumulative.compareTo(payment.getAmount()) >= 0) {

            throw new IllegalStateException(
                    "Historical refunds do not match persisted payment");
        }
    }
}