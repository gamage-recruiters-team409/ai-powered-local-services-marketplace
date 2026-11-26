package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * WP07 implementation of the WP09 dispute settlement contract.
 *
 * Coordinates source-preserving releases and reserved customer refunds.
 * Reports APPLIED only after confirmed financial settlement.
 *
 * Provider penalties remain blocked until their funding and
 * insufficient-balance policies are confirmed.
 */
@Component
@Primary
public class Wp07DisputeSettlementAdapter implements DisputeSettlementPort {

    private final BookingFinancialSnapshotService snapshotService;
    private final DisputePayoutHoldRepository holdRepository;
    private final DisputePayoutHoldTransactionService holdTransactionService;
    private final DisputeSettlementCalculator settlementCalculator;
    private final RefundOperationRepository refundRepository;
    private final RefundTransactionService refundTransactionService;
    private final RefundExecutionService refundExecutionService;
    private final RefundReconciliationService refundReconciliationService;

    public Wp07DisputeSettlementAdapter(
            BookingFinancialSnapshotService snapshotService,
            DisputePayoutHoldRepository holdRepository,
            DisputePayoutHoldTransactionService holdTransactionService,
            DisputeSettlementCalculator settlementCalculator,
            RefundOperationRepository refundRepository,
            RefundTransactionService refundTransactionService,
            RefundExecutionService refundExecutionService,
            RefundReconciliationService refundReconciliationService) {

        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.holdRepository = Objects.requireNonNull(holdRepository);
        this.holdTransactionService =
                Objects.requireNonNull(holdTransactionService);
        this.settlementCalculator =
                Objects.requireNonNull(settlementCalculator);
        this.refundRepository =
                Objects.requireNonNull(refundRepository);
        this.refundTransactionService =
                Objects.requireNonNull(refundTransactionService);
        this.refundExecutionService =
                Objects.requireNonNull(refundExecutionService);
        this.refundReconciliationService =
                Objects.requireNonNull(refundReconciliationService);
    }

    /**
     * Validates trusted settlement instructions before performing
     * any financial operation.
     */
    @Override
    public SettlementOutcome settle(SettlementInstruction instruction) {

        Objects.requireNonNull(
                instruction, "Settlement instruction is required");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Settlement orchestration requires no outer transaction");
        }

        String disputeId = requireText(
                instruction.disputeId(), "Dispute ID");

        String bookingId = requireText(
                instruction.bookingId(), "Booking ID");

        String customerId = requireText(
                instruction.customerId(), "Customer ID");

        String providerId = requireText(
                instruction.providerId(), "Provider ID");

        String expectedKey = "dispute-" + disputeId;

        if (!expectedKey.equals(instruction.idempotencyKey())) {
            throw new SettlementRejectedException(
                    "Unexpected settlement idempotency key");
        }

        if (instruction.action() == null) {
            throw new SettlementRejectedException(
                    "Settlement action is required");
        }

        // Reject unsupported penalties before any financial movement.
        if (instruction.providerPenaltyPercent() != 0) {
            throw new SettlementRejectedException(
                    "Provider penalty settlement policy is not confirmed");
        }

        if (instruction.action() == ResolutionAction.RELEASE_TO_PROVIDER
                && instruction.customerRefundPercent() != 0) {

            throw new SettlementRejectedException(
                    "Provider release cannot include refund or penalty");
        }

        var snapshot = snapshotService.getSnapshot(bookingId);

        if (!customerId.equals(snapshot.customerId())
                || !providerId.equals(snapshot.providerId())
                || !bookingId.equals(snapshot.bookingId())) {

            throw new SettlementRejectedException(
                    "Settlement ownership does not match booking");
        }

        DisputeSettlementCalculator.SettlementAmounts amounts;

        try {
            amounts = settlementCalculator.calculate(
                    snapshot,
                    instruction.action(),
                    instruction.customerRefundPercent(),
                    instruction.providerPenaltyPercent());

        } catch (IllegalArgumentException exception) {
            throw new SettlementRejectedException(
                    "Invalid settlement percentages");
        }

        DisputePayoutHold hold = holdRepository
                .findByDisputeId(disputeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No financial hold exists for this dispute"));

        if (!disputeId.equals(hold.getDisputeId())
                || !bookingId.equals(hold.getBookingId())
                || !providerId.equals(hold.getProviderId())
                || !snapshot.paymentId().equals(hold.getPaymentId())
                || !snapshot.currency().equals(hold.getCurrency())
                || hold.getAmount() == null
                || snapshot.providerEarnings()
                        .compareTo(hold.getAmount()) != 0) {

            throw new SettlementRejectedException(
                    "Settlement does not match original financial hold");
        }

        RefundOperation existing = refundRepository
                .findByIdempotencyKey(expectedKey)
                .orElse(null);

        if (instruction.action() == ResolutionAction.RELEASE_TO_PROVIDER) {

            if (existing != null
                    || snapshot.refundedAmount().signum() != 0) {

                throw new SettlementRejectedException(
                        "Provider release conflicts with refund settlement");
            }

            return releaseProviderEarnings(disputeId);
        }

        return settleCustomerRefund(
                snapshot,
                hold,
                expectedKey,
                amounts.refundAmount(),
                existing);
    }

    /**
     * Preserves the existing source-aware provider release workflow.
     */
    private SettlementOutcome releaseProviderEarnings(String disputeId) {

        DisputePayoutHold released =
                holdTransactionService.releaseHold(disputeId);

        if (released == null
                || released.getStatus()
                        != DisputePayoutHold.HoldStatus.RELEASED) {

            throw new IllegalStateException(
                    "Financial hold release was not confirmed");
        }

        return SettlementOutcome.APPLIED;
    }

    /**
     * Creates or resumes one dispute refund without replaying
     * uncertain gateway creation requests.
     */
    private SettlementOutcome settleCustomerRefund(
            BookingFinancialSnapshotService.FinancialSnapshot snapshot,
            DisputePayoutHold hold,
            String expectedKey,
            BigDecimal refundAmount,
            RefundOperation existing) {

        if (refundAmount.signum() <= 0) {
            throw new SettlementRejectedException(
                    "Dispute refund amount must be positive");
        }

        RefundOperation operation = existing;

        if (operation == null) {

            if (snapshot.refundedAmount().signum() != 0
                    || hold.getStatus()
                            != DisputePayoutHold.HoldStatus.APPLIED) {

                throw new SettlementRejectedException(
                        "Dispute refund requires an unused financial hold");
            }

            operation = refundTransactionService.reserveDisputeRefund(
                    snapshot.paymentId(),
                    hold.getDisputeId(),
                    refundAmount);
        }

        validateRefundIdentity(
                operation, snapshot, hold, expectedKey, refundAmount);

        // Only a newly reserved operation may create a gateway refund.
        switch (operation.getStatus()) {

            case PENDING -> operation =
                    refundExecutionService.executeReservedRefund(
                            operation.getId());

            case PROCESSING, REQUIRES_RECONCILIATION -> operation =
                    refundReconciliationService.reconcileRefund(
                            operation.getId());

            case SUCCEEDED, FAILED -> {
                // Terminal operations must not be submitted again.
            }
        }

        validateRefundIdentity(
                operation, snapshot, hold, expectedKey, refundAmount);

        if (operation.getStatus() == RefundStatus.FAILED) {
            throw new SettlementRejectedException(
                    "Dispute refund failed; automatic replay is prohibited");
        }

        if (operation.getStatus() != RefundStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "Dispute refund settlement is not yet confirmed");
        }

        // Re-read committed financial state before reporting success.
        DisputePayoutHold settled = holdRepository
                .findByDisputeId(hold.getDisputeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Settled dispute hold was not found"));

        if (settled.getStatus() != DisputePayoutHold.HoldStatus.SETTLED
                || !Objects.equals(hold.getId(), settled.getId())
                || !Objects.equals(
                        hold.getPaymentId(), settled.getPaymentId())
                || !Objects.equals(
                        hold.getBookingId(), settled.getBookingId())
                || !Objects.equals(
                        hold.getProviderId(), settled.getProviderId())
                || !Objects.equals(
                        hold.getCurrency(), settled.getCurrency())
                || settled.getAmount() == null
                || hold.getAmount().compareTo(settled.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Dispute refund hold settlement was not confirmed");
        }

        var committedSnapshot =
                snapshotService.getSnapshot(snapshot.bookingId());

        BigDecimal expectedRefundedAmount = operation.getReservation()
                .previouslyRefundedAmount()
                .add(operation.getAmount());

        if (!snapshot.paymentId().equals(committedSnapshot.paymentId())
                || !snapshot.customerId().equals(committedSnapshot.customerId())
                || !snapshot.providerId().equals(committedSnapshot.providerId())
                || !snapshot.currency().equals(committedSnapshot.currency())
                || committedSnapshot.refundedAmount()
                        .compareTo(expectedRefundedAmount) != 0) {

            throw new IllegalStateException(
                    "Committed payment refund amount was not confirmed");
        }

        return SettlementOutcome.APPLIED;
    }

    /**
     * Prevents an idempotency key from being reused for a different
     * payment, dispute, provider or refund amount.
     */
    private static void validateRefundIdentity(
            RefundOperation operation,
            BookingFinancialSnapshotService.FinancialSnapshot snapshot,
            DisputePayoutHold hold,
            String expectedKey,
            BigDecimal refundAmount) {

        if (operation == null
                || operation.getId() == null
                || operation.getId().isBlank()
                || !expectedKey.equals(operation.getIdempotencyKey())
                || !hold.getDisputeId().equals(operation.getDisputeId())
                || !snapshot.paymentId().equals(operation.getPaymentId())
                || !snapshot.bookingId().equals(operation.getBookingId())
                || !snapshot.currency().equals(operation.getCurrency())
                || operation.getAmount() == null
                || refundAmount.compareTo(operation.getAmount()) != 0
                || operation.getReservation() == null
                || !snapshot.providerId().equals(
                        operation.getReservation().providerId())) {

            throw new SettlementRejectedException(
                    "Dispute refund identity or financial amount mismatch");
        }
    }

    private static String requireText(String value, String field) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }

        return value.trim();
    }
}