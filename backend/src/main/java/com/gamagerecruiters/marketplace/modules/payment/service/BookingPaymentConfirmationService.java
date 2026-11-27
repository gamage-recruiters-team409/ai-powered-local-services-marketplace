package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.booking.service.TrustedBookingSystemCall;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * Synchronizes verified Stripe payment captures with the WP05 booking
 * lifecycle.
 *
 * <p>
 * Booking confirmation must participate in the existing payment capture
 * transaction. This service must only be invoked after webhook signature,
 * payment association, amount, currency and PaymentIntent verification.
 * </p>
 *
 * <p>
 * Booking changes are performed exclusively through the public BookingFacade
 * contract. No direct access to booking persistence is permitted.
 * </p>
 */
@Service
public class BookingPaymentConfirmationService {

    private final BookingFacade bookingFacade;

    public BookingPaymentConfirmationService(BookingFacade bookingFacade) {
        this.bookingFacade = Objects.requireNonNull(
                bookingFacade, "BookingFacade is required");
    }

    /**
     * Confirms the booking associated with a verified payment.
     *
     * <p>
     * The internal payment ID is used as the stable confirmation reference.
     * WP05 provides idempotency for repeated confirmation requests using the
     * same reference.
     * </p>
     *
     * @param payment persisted payment associated with the verified Stripe event
     * @return booking participants returned by the booking facade
     */
    public BookingParticipants confirmVerifiedPayment(Payment payment) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Booking payment confirmation requires an active transaction");
        }

        Objects.requireNonNull(payment, "Payment is required");

        String paymentId = requireText(payment.getId(), "Payment ID");
        String bookingId = requireText(payment.getBookingId(), "Booking ID");
        String customerId = requireText(payment.getCustomerId(), "Customer ID");

        if (!paymentId.matches("[A-Za-z0-9_:.\\-]{1,100}")) {
            throw new IllegalStateException(
                    "Payment ID is not a valid booking confirmation reference");
        }

        SecurityContext previousContext = SecurityContextHolder.getContext();

        Authentication authentication = previousContext.getAuthentication();

        // Never elevate an authenticated application user or another
        // internal authentication token to PAYMENT_PROCESSING.
        if (authentication != null
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            throw new IllegalStateException(
                    "Booking payment confirmation requires a trusted webhook context");
        }

        try {
            // Spring Security represents unauthenticated HTTP requests
            // using an authenticated anonymous token. Remove only this
            // anonymous marker before entering the WP05 trust boundary.
            if (authentication instanceof AnonymousAuthenticationToken) {
                SecurityContextHolder.setContext(
                        SecurityContextHolder.createEmptyContext());
            }

            return TrustedBookingSystemCall.run(
                    TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                    () -> {

                        BookingPaymentSnapshot snapshot = bookingFacade.getPaymentSnapshot(bookingId);

                        validateFinancialSnapshot(
                                payment,
                                snapshot,
                                bookingId,
                                customerId);

                        // WP05 enforces valid booking transitions and
                        // rejects conflicting payment references.
                        BookingParticipants confirmed = bookingFacade.markPaymentConfirmed(
                                bookingId,
                                paymentId);

                        if (confirmed == null) {
                            throw new IllegalStateException(
                                    "Booking confirmation returned no result");
                        }

                        return confirmed;
                    });

        } finally {
            // Restore the original request context even when booking
            // validation or confirmation fails.
            SecurityContextHolder.setContext(previousContext);
        }
    }

    /**
     * Validates the persisted payment against the authoritative booking
     * financial snapshot before applying a booking status change.
     */
    private static void validateFinancialSnapshot(
            Payment payment,
            BookingPaymentSnapshot snapshot,
            String bookingId,
            String customerId) {

        if (snapshot == null) {
            throw new IllegalStateException(
                    "Trusted booking payment snapshot is missing");
        }

        if (!bookingId.equals(snapshot.bookingId())) {
            throw new IllegalStateException(
                    "Booking payment snapshot identity mismatch");
        }

        if (!customerId.equals(snapshot.customerId())) {
            throw new IllegalStateException(
                    "Booking payment customer identity mismatch");
        }

        if (payment.getAmount() == null
                || payment.getAmount().signum() <= 0
                || !sameDecimal(payment.getAmount(), snapshot.amount())) {
            throw new IllegalStateException(
                    "Booking payment amount mismatch");
        }

        if (payment.getCurrency() == null
                || payment.getCurrency().isBlank()
                || snapshot.currency() == null
                || !payment.getCurrency().equalsIgnoreCase(
                        snapshot.currency())) {
            throw new IllegalStateException(
                    "Booking payment currency mismatch");
        }

        BigDecimal commissionRate = snapshot.commissionRate();

        if (commissionRate == null
                || commissionRate.signum() < 0
                || commissionRate.compareTo(BigDecimal.ONE) > 0
                || !sameDecimal(
                        payment.getCommissionRate(),
                        commissionRate)) {
            throw new IllegalStateException(
                    "Booking payment commission rate mismatch");
        }

        BigDecimal expectedCommission = snapshot.amount()
                .multiply(commissionRate)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        if (!sameDecimal(
                payment.getCommissionAmount(),
                expectedCommission)) {
            throw new IllegalStateException(
                    "Booking payment commission amount mismatch");
        }

        if (snapshot.priceCapturedAt() == null
                || snapshot.acceptedAt() == null
                || snapshot.paymentDueAt() == null
                || !snapshot.paymentDueAt().isAfter(
                        snapshot.acceptedAt())) {
            throw new IllegalStateException(
                    "Booking payment deadline snapshot is invalid");
        }
    }

    /**
     * Compares monetary values without requiring identical decimal scales.
     */
    private static boolean sameDecimal(
            BigDecimal first,
            BigDecimal second) {

        return first != null
                && second != null
                && first.compareTo(second) == 0;
    }

    private static String requireText(
            String value,
            String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    fieldName + " is required");
        }

        return value.trim();
    }
}