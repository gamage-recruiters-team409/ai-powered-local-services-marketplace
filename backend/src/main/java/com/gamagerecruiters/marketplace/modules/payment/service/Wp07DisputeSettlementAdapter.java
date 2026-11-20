package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;

/**
 * WP07 implementation of the WP09 dispute settlement contract.
 *
 * Only the source-preserving provider release path is currently supported.
 * Refund decisions fail closed until commission allocation and atomic
 * refund accounting have been implemented and approved.
 */
@Component
@Primary
public class Wp07DisputeSettlementAdapter implements DisputeSettlementPort {

    private final BookingFinancialSnapshotService snapshotService;
    private final DisputePayoutHoldRepository holdRepository;
    private final DisputePayoutHoldTransactionService holdTransactionService;

    public Wp07DisputeSettlementAdapter(
            BookingFinancialSnapshotService snapshotService,
            DisputePayoutHoldRepository holdRepository,
            DisputePayoutHoldTransactionService holdTransactionService) {

        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.holdRepository = Objects.requireNonNull(holdRepository);
        this.holdTransactionService =
                Objects.requireNonNull(holdTransactionService);
    }

    /**
     * Reports APPLIED only after the underlying MongoDB release operation
     * returns a confirmed RELEASED hold.
     */
    @Override
    public SettlementOutcome settle(SettlementInstruction instruction) {

        Objects.requireNonNull(
                instruction, "Settlement instruction is required");

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

        // Do not apply unsupported refund or penalty movements.
        if (instruction.action() != ResolutionAction.RELEASE_TO_PROVIDER) {
            throw new SettlementRejectedException(
                    "Refund settlement requires approved commission allocation "
                    + "and atomic refund accounting");
        }

        if (instruction.customerRefundPercent() != 0
                || instruction.providerPenaltyPercent() != 0) {
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

        if (snapshot.refundedAmount().signum() != 0) {
            throw new SettlementRejectedException(
                    "Provider release is not allowed after a refund");
        }

        DisputePayoutHold hold = holdRepository
                .findByDisputeId(disputeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No financial hold exists for this dispute"));

        if (!bookingId.equals(hold.getBookingId())
                || !providerId.equals(hold.getProviderId())
                || !snapshot.paymentId().equals(hold.getPaymentId())
                || !snapshot.currency().equals(hold.getCurrency())
                || snapshot.providerEarnings()
                        .compareTo(hold.getAmount()) != 0) {
            throw new SettlementRejectedException(
                    "Settlement does not match original financial hold");
        }

        // This service verifies the original hold events and commits
        // source-preserving release events with the hold status.
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}