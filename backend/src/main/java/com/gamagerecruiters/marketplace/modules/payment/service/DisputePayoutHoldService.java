
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Prepares booking-specific payout holds requested by the Dispute module.
 *
 * <p>
 * This service validates booking ownership and the originating payment
 * before creating a pending hold record.
 * </p>
 *
 * <p>
 * Preparing a hold does not reserve provider funds. Actual financial
 * reservation must be verified before the hold can be marked as applied.
 * </p>
 */
@Service
public class DisputePayoutHoldService {

    private final DisputePayoutHoldRepository holdRepository;
    private final PaymentRepository paymentRepository;
    private final BookingFacade bookingFacade;
    private final EarningsLedgerRepository ledgerRepository;
    private final EarningsBalanceCalculator balanceCalculator;

    public DisputePayoutHoldService(
            DisputePayoutHoldRepository holdRepository,
            PaymentRepository paymentRepository,
            BookingFacade bookingFacade,
            EarningsLedgerRepository ledgerRepository,
            EarningsBalanceCalculator balanceCalculator) {

        this.holdRepository = Objects.requireNonNull(
                holdRepository, "Hold repository is required");

        this.paymentRepository = Objects.requireNonNull(
                paymentRepository, "Payment repository is required");

        this.bookingFacade = Objects.requireNonNull(
                bookingFacade, "Booking facade is required");

        this.ledgerRepository = Objects.requireNonNull(
                ledgerRepository, "Ledger repository is required");

        this.balanceCalculator = Objects.requireNonNull(
                balanceCalculator, "Balance calculator is required");
    }

    /**
     * Creates or retrieves a pending payout hold for a dispute.
     *
     * <p>
     * Repeated requests must reference the same booking and provider.
     * This method does not perform a ledger transfer or external
     * payment operation.
     * </p>
     *
     * @param disputeId  verified dispute identifier
     * @param bookingId  verified booking identifier
     * @param providerId verified provider identifier
     * @return existing or newly prepared hold record
     */
    public DisputePayoutHold prepareHold(
            String disputeId,
            String bookingId,
            String providerId) {

        requireText(disputeId, "Dispute ID");
        requireText(bookingId, "Booking ID");
        requireText(providerId, "Provider ID");

        // Verify booking ownership through the official Booking module facade.
        BookingParticipants booking = Objects.requireNonNull(
                bookingFacade.getBookingParticipants(bookingId),
                "Booking participants are required");

        if (!bookingId.equals(booking.bookingId())
                || !providerId.equals(booking.providerId())) {
            throw new IllegalStateException(
                    "Dispute payout hold does not match the booking provider");
        }

        if (booking.customerId() == null
                || booking.customerId().isBlank()) {
            throw new IllegalStateException(
                    "Booking customer information is missing");
        }

        // Resolve repeated requests before attempting a new write.
        var existing = holdRepository.findByDisputeId(disputeId);

        if (existing.isPresent()) {
            DisputePayoutHold hold = existing.get();

            if (!bookingId.equals(hold.getBookingId())
                    || !providerId.equals(hold.getProviderId())) {
                throw new IllegalStateException(
                        "Dispute ID is already associated with a different booking or provider");
            }

            return hold;
        }

        // A booking may have multiple payment attempts.
        List<Payment> payments = paymentRepository.findByBookingId(bookingId);

        if (payments == null || payments.isEmpty()) {
            throw new IllegalStateException(
                    "No payment record found for the disputed booking");
        }

        List<Payment> eligiblePayments = payments.stream()
                .filter(this::isEligiblePayment)
                .toList();

        // Fail closed instead of choosing an arbitrary payment attempt.
        if (eligiblePayments.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one eligible payment for the disputed booking");
        }

        Payment payment = eligiblePayments.get(0);

        // Verify that the payment belongs to the booking and its customer.
        if (!bookingId.equals(payment.getBookingId())
                || payment.getCustomerId() == null
                || !booking.customerId().equals(payment.getCustomerId())) {
            throw new IllegalStateException(
                    "Payment ownership does not match the disputed booking");
        }

        BigDecimal commission = payment.getCommissionAmount();

        if (commission == null || commission.signum() < 0) {
            throw new IllegalStateException(
                    "Payment commission snapshot is missing or invalid");
        }

        BigDecimal providerAmount = payment.getAmount().subtract(commission);

        if (providerAmount.signum() <= 0
                || providerAmount.stripTrailingZeros().scale() > 2) {
            throw new IllegalStateException(
                    "Calculated provider earnings are invalid");
        }

        // PENDING means the financial reservation has not been confirmed.
        DisputePayoutHold hold = new DisputePayoutHold(
                disputeId,
                bookingId,
                payment.getId(),
                providerId,
                providerAmount,
                payment.getCurrency());

        return holdRepository.save(hold);
    }

    /**
     * Determines whether a payment is a candidate for dispute handling.
     *
     * <p>
     * This check does not prove that earnings are available or reserved.
     * Those conditions must be verified before applying a financial hold.
     * </p>
     */
    /**
     * Calculates earnings belonging to one verified booking and payment.
     *
     * <p>
     * This read-only operation does not reserve funds or guarantee
     * that balances remain unchanged after calculation.
     * </p>
     *
     * @param hold validated dispute payout hold
     * @return booking-specific earnings balance
     */
    public EarningsBalanceCalculator.EarningsBalance
            calculateBookingEarnings(DisputePayoutHold hold) {

        Objects.requireNonNull(hold, "Payout hold is required");

        List<EarningsLedgerEntry> entries =
                ledgerRepository.findByBookingIdOrderByCreatedAtDesc(
                        hold.getBookingId());

        List<EarningsLedgerEntry> matchingEntries = entries.stream()
                .filter(entry -> hold.getPaymentId().equals(entry.getPaymentId()))
                .filter(entry -> hold.getProviderId().equals(entry.getProviderId()))
                .filter(entry -> hold.getCurrency().equals(entry.getCurrency()))
                .toList();

        if (matchingEntries.isEmpty()) {
            throw new IllegalStateException(
                    "No earnings ledger events found for the disputed payment");
        }

        // Fail closed when booking history contains unexpected ownership.
        if (matchingEntries.size() != entries.size()) {
            throw new IllegalStateException(
                    "Booking earnings ledger contains conflicting payment or provider data");
        }

        return balanceCalculator.calculate(matchingEntries);
    }
    private boolean isEligiblePayment(Payment payment) {

        if (payment == null
                || payment.getId() == null
                || payment.getId().isBlank()
                || payment.getAmount() == null
                || payment.getAmount().signum() <= 0
                || payment.getCurrency() == null
                || payment.getCurrency().isBlank()) {
            return false;
        }

        PaymentStatus status = payment.getStatus();

        return status == PaymentStatus.CAPTURED
                || status == PaymentStatus.RELEASED;
    }

    /**
     * Rejects missing identifiers before performing repository operations.
     */
    private static void requireText(String value, String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }
    }
}

