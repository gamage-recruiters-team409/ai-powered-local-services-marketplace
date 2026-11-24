
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Coordinates the initial provider earnings lifecycle after payment capture.
 *
 * <p>
 * This service records pending earnings for a persisted, captured payment.
 * It uses the trusted BookingFacade to identify the associated provider
 * and delegates append-only persistence to EarningsLedgerService.
 * </p>
 *
 * <p>
 * This is an internal foundation service. It does not authenticate
 * payment gateway notifications, capture funds, change payment statuses,
 * release earnings, or initiate payouts.
 * </p>
 *
 * <p>
 * Callers must ensure that the CAPTURED status originates from a
 * verified gateway workflow. Production integration must also provide
 * reliable recovery for interrupted payment-to-ledger processing.
 * </p>
 */
@Service
public class EarningsLifecycleService {

    private static final String PENDING_EVENT_PREFIX = "earning-pending:";

    private final PaymentPersistenceService paymentPersistenceService;
    private final BookingFacade bookingFacade;
    private final CompletionLookup completionLookup;
    private final EarningsLedgerService earningsLedgerService;
    private final EarningsRecognitionTransactionService recognitionTransactionService;

    /**
     * Creates the lifecycle service with its trusted dependencies.
     *
     * @param paymentPersistenceService service for retrieving payments
     * @param bookingFacade             trusted booking information interface
     * @param completionLookup          verified service completion lookup
     * @param earningsLedgerService     append-only earnings ledger service
     * @param recognitionTransactionService atomic earnings recognition service
     */
    public EarningsLifecycleService(
            PaymentPersistenceService paymentPersistenceService,
            BookingFacade bookingFacade,
            CompletionLookup completionLookup,
            EarningsLedgerService earningsLedgerService,
            EarningsRecognitionTransactionService recognitionTransactionService) {

        this.paymentPersistenceService = Objects.requireNonNull(
                paymentPersistenceService,
                "Payment persistence service is required");

        this.bookingFacade = Objects.requireNonNull(
                bookingFacade,
                "Booking facade is required");

        this.completionLookup = Objects.requireNonNull(
                completionLookup,
                "Completion lookup is required");

        this.earningsLedgerService = Objects.requireNonNull(
                earningsLedgerService,
                "Earnings ledger service is required");

        this.recognitionTransactionService = Objects.requireNonNull(
                recognitionTransactionService,
                "Earnings recognition transaction service is required");
    }

    /**
     * Records provider earnings as pending after a verified payment capture.
     *
     * <p>
     * A deterministic event key is derived from the persisted payment ID.
     * Repeated processing therefore uses the same key, allowing the
     * earnings ledger service to apply its idempotency checks.
     * </p>
     *
     * @param paymentId identifier of the persisted payment
     * @return newly inserted or previously recorded pending earnings event
     * @throws IllegalArgumentException if paymentId is missing
     * @throws IllegalStateException    if the payment or financial data is invalid
     */
    public EarningsLedgerEntry recordPendingEarnings(String paymentId) {

        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "Payment ID must not be blank");
        }

        String normalizedPaymentId = paymentId.trim();

        // Only persisted payment records may generate earnings.
        Payment payment = paymentPersistenceService
                .findPaymentById(normalizedPaymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found: " + normalizedPaymentId));

        // Ensure the retrieved record matches the requested payment ID.
        String persistedPaymentId = requireNonBlank(
                payment.getId(),
                "Persisted payment ID");

        if (!normalizedPaymentId.equals(persistedPaymentId)) {
            throw new IllegalStateException(
                    "Persisted payment ID does not match the requested payment");
        }

        // Payment initiation or authorization does not establish earnings.
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                    "Pending earnings require a CAPTURED payment");
        }

        BigDecimal netEarnings = calculateNetEarnings(payment);

        String bookingId = requireNonBlank(
                payment.getBookingId(),
                "Payment booking ID");

        // Obtain provider identity from the trusted booking module.
        BookingParticipants participants = bookingFacade.getBookingParticipants(bookingId);

        if (participants == null) {
            throw new IllegalStateException(
                    "BookingFacade returned no booking participants");
        }

        if (!bookingId.equals(participants.bookingId())) {
            throw new IllegalStateException(
                    "Booking participants do not match the payment booking");
        }

        // Verify that the persisted payment belongs to the booking customer.
        // A mismatched customer must never receive a provider earnings event.
        String paymentCustomerId = requireNonBlank(
                payment.getCustomerId(),
                "Payment customer ID");

        String bookingCustomerId = requireNonBlank(
                participants.customerId(),
                "Booking customer ID");

        if (!paymentCustomerId.equals(bookingCustomerId)) {
            throw new IllegalStateException(
                    "Payment customer does not match the booking customer");
        }

        String providerId = requireNonBlank(
                participants.providerId(),
                "Booking provider ID");

        // Pending earnings require verified service completion, not payment capture alone.
        // The current booking must be completed and WP08 must retain confirmation evidence.
        if (participants.status() != BookingStatus.COMPLETED
                && participants.status() != BookingStatus.DISPUTED) {
            throw new IllegalStateException(
                    "Pending earnings require a COMPLETED booking");
        }

        if (completionLookup.findConfirmedAt(bookingId).isEmpty()) {
            throw new IllegalStateException(
                    "Pending earnings require verified service completion");
        }

        // Construct an append-only event for this specific payment capture.
        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(PENDING_EVENT_PREFIX + normalizedPaymentId);
        entry.setPaymentId(normalizedPaymentId);
        entry.setBookingId(bookingId);
        entry.setProviderId(providerId);
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setAmount(netEarnings);
        entry.setCurrency(requireNonBlank(
                payment.getCurrency(),
                "Payment currency"));
        entry.setCreatedAt(Instant.now());

        // The ledger service validates and enforces event-key idempotency.
        if (participants.status() == BookingStatus.DISPUTED) {
            return earningsLedgerService.findMatchingCommittedEvent(entry)
                    .orElseThrow(() -> new IllegalStateException(
                            "Disputed booking has no previously committed pending earnings event"));
        }

        // Serialize earnings recognition with concurrent refund reservations.
        return recognitionTransactionService.recognizeAtomically(
                payment, entry);
    }

    /**
     * Calculates provider earnings from the persisted commission snapshot.
     *
     * <p>
     * Commission values are not recalculated because the payment already
     * contains the commission amount established at creation time.
     * </p>
     *
     * @param payment persisted captured payment
     * @return non-negative provider earnings amount
     */
    private BigDecimal calculateNetEarnings(Payment payment) {

        BigDecimal amount = payment.getAmount();
        BigDecimal commission = payment.getCommissionAmount();

        if (amount == null || amount.signum() <= 0
                || commission == null || commission.signum() < 0) {
            throw new IllegalStateException(
                    "Payment amount or commission snapshot is invalid");
        }

        if (amount.stripTrailingZeros().scale() > 2
                || commission.stripTrailingZeros().scale() > 2) {
            throw new IllegalStateException(
                    "Payment amounts must not exceed two decimal places");
        }

        if (commission.compareTo(amount) > 0) {
            throw new IllegalStateException(
                    "Commission cannot exceed the payment amount");
        }

        return amount.subtract(commission);
    }

    /**
     * Requires a non-blank identifier or value.
     *
     * @param value     value to validate
     * @param fieldName field description for error messages
     * @return trimmed value
     */
    private String requireNonBlank(String value, String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    fieldName + " must not be blank");
        }

        return value.trim();
    }
}
