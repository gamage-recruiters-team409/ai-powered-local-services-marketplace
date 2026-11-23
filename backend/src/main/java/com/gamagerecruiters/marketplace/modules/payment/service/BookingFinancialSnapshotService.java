
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Provides verified, booking-specific financial amounts for internal
 * payment and dispute settlement workflows.
 *
 * This service is read-only. It does not refund, release, or transfer funds.
 */
@Service
public class BookingFinancialSnapshotService {

    private final PaymentPersistenceService paymentPersistenceService;
    private final BookingFacade bookingFacade;

    public BookingFinancialSnapshotService(
            PaymentPersistenceService paymentPersistenceService,
            BookingFacade bookingFacade) {

        this.paymentPersistenceService = Objects.requireNonNull(
                paymentPersistenceService, "Payment persistence service is required");

        this.bookingFacade = Objects.requireNonNull(
                bookingFacade, "Booking facade is required");
    }

    /**
     * Retrieves one verified payment snapshot for a booking.
     *
     * Rejects missing payments, ambiguous eligible payment attempts,
     * mismatched ownership, and invalid commission snapshots.
     */
    public FinancialSnapshot getSnapshot(String bookingId) {

        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException("Booking ID is required");
        }

        String normalizedBookingId = bookingId.trim();

        BookingParticipants booking = Objects.requireNonNull(
                bookingFacade.getBookingParticipants(normalizedBookingId),
                "Booking participants are required");

        if (!normalizedBookingId.equals(booking.bookingId())) {
            throw new IllegalStateException(
                    "Booking identity does not match the requested booking");
        }

        String customerId = requireText(
                booking.customerId(), "Booking customer ID");

        String providerId = requireText(
                booking.providerId(), "Booking provider ID");

        List<Payment> payments = paymentPersistenceService
                .findPaymentsByBookingId(normalizedBookingId);

        if (payments == null) {
            throw new IllegalStateException(
                    "Payment lookup returned no records");
        }

        // A booking can have failed or abandoned payment attempts.
        // Never select an arbitrary eligible financial transaction.
        List<Payment> eligiblePayments = payments.stream()
                .filter(Objects::nonNull)
                .filter(payment -> payment.getStatus() == PaymentStatus.CAPTURED
                        || payment.getStatus() == PaymentStatus.RELEASED
                        || payment.getStatus() == PaymentStatus.REFUND_PENDING
                        || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                        || payment.getStatus() == PaymentStatus.REFUNDED)
                .toList();

        if (eligiblePayments.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one financially relevant payment for booking");
        }

        Payment payment = eligiblePayments.get(0);

        String paymentId = requireText(
                payment.getId(), "Payment ID");

        if (!normalizedBookingId.equals(payment.getBookingId())
                || !customerId.equals(payment.getCustomerId())) {
            throw new IllegalStateException(
                    "Payment ownership does not match booking");
        }

        String currency = requireText(
                payment.getCurrency(), "Payment currency");

        BigDecimal amount = requireMoney(
                payment.getAmount(), "Payment amount");

        BigDecimal commission = requireMoney(
                payment.getCommissionAmount(), "Commission amount");

        if (amount.signum() <= 0 || commission.compareTo(amount) > 0) {
            throw new IllegalStateException(
                    "Invalid captured amount or commission snapshot");
        }

        BigDecimal refundedAmount = payment.getRefundedAmount() == null
                ? BigDecimal.ZERO
                : requireMoney(payment.getRefundedAmount(), "Refunded amount");

        if (refundedAmount.compareTo(amount) > 0) {
            throw new IllegalStateException(
                    "Refunded amount exceeds original payment");
        }

        return new FinancialSnapshot(
                paymentId,
                normalizedBookingId,
                customerId,
                providerId,
                currency,
                payment.getStatus(),
                amount,
                commission,
                amount.subtract(commission),
                refundedAmount);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required");
        }
        return value.trim();
    }

    private static BigDecimal requireMoney(
            BigDecimal amount, String field) {

        if (amount == null
                || amount.signum() < 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalStateException(field + " is invalid");
        }

        return amount;
    }

    /**
     * Immutable financial snapshot derived from persisted payment data
     * and trusted booking ownership.
     */
    public record FinancialSnapshot(
            String paymentId,
            String bookingId,
            String customerId,
            String providerId,
            String currency,
            PaymentStatus paymentStatus,
            BigDecimal capturedAmount,
            BigDecimal commissionAmount,
            BigDecimal providerEarnings,
            BigDecimal refundedAmount) {
    }
}
