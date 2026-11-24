package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Determines payment-specific provider earnings allocations for refunds.
 *
 * This service performs read-only financial validation and calculation.
 * It does not persist reservations or execute external gateway refunds.
 *
 * The caller must calculate and persist the allocation within the
 * same financial transaction, with appropriate concurrency protection.
 */
@Service
public class RefundEarningsAllocationService {

    private final EarningsLedgerRepository ledgerRepository;
    private final EarningsBalanceCalculator balanceCalculator;

    public RefundEarningsAllocationService(
            EarningsLedgerRepository ledgerRepository,
            EarningsBalanceCalculator balanceCalculator) {

        this.ledgerRepository = Objects.requireNonNull(
                ledgerRepository, "Ledger repository is required");

        this.balanceCalculator = Objects.requireNonNull(
                balanceCalculator, "Balance calculator is required");
    }

    /**
     * Calculates the earnings allocation for a proposed refund.
     *
     * The provider identity must originate from trusted booking data.
     * Unrecognized earnings are permitted only when the calling workflow
     * has independently verified that earnings were not yet established.
     *
     * Dispute-held earnings require a separate authorized settlement
     * workflow and cannot be consumed by an ordinary refund.
     */
    public RefundReservation calculateAllocation(
            Payment payment,
            String providerId,
            RefundCommissionCalculator.RefundAmounts refundAmounts,
            boolean verifiedUnrecognizedEarnings) {

        Objects.requireNonNull(payment, "Payment is required");
        Objects.requireNonNull(refundAmounts, "Refund amounts are required");

        String paymentId = requireText(payment.getId(), "Payment ID");
        String bookingId = requireText(payment.getBookingId(), "Booking ID");
        String currency = requireText(payment.getCurrency(), "Currency");
        String trustedProviderId = requireText(providerId, "Provider ID");

        BigDecimal requiredReversal =
                refundAmounts.providerEarningsReversalAmount();

        Objects.requireNonNull(
                requiredReversal, "Provider reversal amount is required");

        if (requiredReversal.signum() < 0) {
            throw new IllegalArgumentException(
                    "Provider reversal amount must not be negative");
        }

        List<EarningsLedgerEntry> entries =
                ledgerRepository.findByPaymentIdOrderByCreatedAtDesc(
                        paymentId);

        // Prevent allocations from unrelated financial records.
        for (EarningsLedgerEntry entry : entries) {

            entry.validate();

            if (!paymentId.equals(entry.getPaymentId())
                    || !bookingId.equals(entry.getBookingId())
                    || !trustedProviderId.equals(entry.getProviderId())
                    || !currency.equals(entry.getCurrency())) {

                throw new IllegalStateException(
                        "Ledger entry does not match the refund payment");
            }
        }

        BigDecimal pendingAllocation = BigDecimal.ZERO;
        BigDecimal availableAllocation = BigDecimal.ZERO;
        BigDecimal heldAllocation = BigDecimal.ZERO;

        if (entries.isEmpty()) {

            // Missing ledger records alone do not prove that earnings
            // were never recognized.
            if (requiredReversal.signum() > 0
                    && !verifiedUnrecognizedEarnings) {

                throw new IllegalStateException(
                        "Unrecognized provider earnings have not been verified");
            }

        } else {

            EarningsBalanceCalculator.EarningsBalance balance =
                    balanceCalculator.calculate(entries);

            boolean hasRecognizedEarnings = entries.stream()
                    .anyMatch(entry -> entry.getEventType()
                            == EarningsLedgerEventType.EARNING_PENDING);

            if (!hasRecognizedEarnings) {
                throw new IllegalStateException(
                        "Payment ledger has no initial earnings event");
            }

            // Ordinary refunds must not interfere with active disputes.
            if (balance.held().signum() > 0) {
                throw new IllegalStateException(
                        "Dispute-held earnings require authorized settlement");
            }

            if (balance.refundReserved().signum() > 0) {
                throw new IllegalStateException(
                        "Payment already has reserved refund earnings");
            }

            // Do not consume funds reserved for an existing payout.
            if (balance.payoutReserved().signum() > 0) {
                throw new IllegalStateException(
                        "Payment has an active payout reservation");
            }

            // Allocate pending earnings first, then available earnings.
            pendingAllocation =
                    balance.pending().min(requiredReversal);

            BigDecimal remaining =
                    requiredReversal.subtract(pendingAllocation);

            availableAllocation =
                    balance.available().min(remaining);

            remaining = remaining.subtract(availableAllocation);

            if (remaining.signum() > 0) {
                throw new IllegalStateException(
                        "Insufficient payment-specific refundable earnings");
            }
        }

        return new RefundReservation(
                payment.getStatus(),
                payment.getRefundedAmount(),
                refundAmounts.commissionReversalAmount(),
                requiredReversal,
                pendingAllocation,
                availableAllocation,
                heldAllocation,
                trustedProviderId);
    }

    /**
     * Validates required financial identifiers.
     */
    /**
     * Calculates a refund allocation exclusively from an applied
     * dispute hold belonging to the original payment.
     *
     * The caller must verify the original hold events and claim
     * the hold within the same financial transaction.
     */
    public RefundReservation calculateDisputeAllocation(
            Payment payment,
            DisputePayoutHold hold,
            RefundCommissionCalculator.RefundAmounts refundAmounts) {

        Objects.requireNonNull(payment, "Payment is required");
        Objects.requireNonNull(hold, "Dispute hold is required");
        Objects.requireNonNull(refundAmounts, "Refund amounts are required");

        if (hold.getStatus() != DisputePayoutHold.HoldStatus.APPLIED
                || !Objects.equals(payment.getId(), hold.getPaymentId())
                || !Objects.equals(payment.getBookingId(), hold.getBookingId())
                || !Objects.equals(payment.getCurrency(), hold.getCurrency())) {

            throw new IllegalStateException(
                    "Refund does not match an applied dispute hold");
        }

        String providerId = requireText(
                hold.getProviderId(), "Hold provider ID");

        BigDecimal required =
                refundAmounts.providerEarningsReversalAmount();

        if (required == null || required.signum() < 0
                || hold.getAmount() == null
                || hold.getAmount().signum() <= 0
                || required.compareTo(hold.getAmount()) > 0) {

            throw new IllegalStateException(
                    "Invalid dispute refund allocation amount");
        }

        List<EarningsLedgerEntry> entries =
                ledgerRepository.findByPaymentIdOrderByCreatedAtDesc(
                        payment.getId());

        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException(
                    "Dispute payment ledger is unavailable");
        }

        for (EarningsLedgerEntry entry : entries) {
            entry.validate();

            if (!Objects.equals(payment.getId(), entry.getPaymentId())
                    || !Objects.equals(payment.getBookingId(), entry.getBookingId())
                    || !Objects.equals(providerId, entry.getProviderId())
                    || !Objects.equals(payment.getCurrency(), entry.getCurrency())) {

                throw new IllegalStateException(
                        "Dispute ledger ownership mismatch");
            }
        }

        boolean recognized = entries.stream().anyMatch(
                entry -> entry.getEventType()
                        == EarningsLedgerEventType.EARNING_PENDING);

        if (!recognized) {
            throw new IllegalStateException(
                    "Dispute payment has no recognized earnings");
        }

        EarningsBalanceCalculator.EarningsBalance balance =
                balanceCalculator.calculate(entries);

        if (balance.held().compareTo(hold.getAmount()) != 0
                || balance.refundReserved().signum() != 0
                || balance.payoutReserved().signum() != 0) {

            throw new IllegalStateException(
                    "Dispute hold balance is inconsistent or already reserved");
        }

        return new RefundReservation(
                payment.getStatus(),
                payment.getRefundedAmount(),
                refundAmounts.commissionReversalAmount(),
                required,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                required,
                providerId);
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