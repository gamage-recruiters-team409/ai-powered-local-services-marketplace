package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;

/**
 * Connects WP07 payment processing to the authoritative WP05 booking
 * payment snapshot.
 *
 * <p>
 * Financial values are obtained exclusively through the BookingFacade.
 * Client-supplied amounts, currencies, commission rates and acceptance
 * timestamps are never used as authoritative payment information.
 * </p>
 *
 * <p>
 * The adapter preserves the existing booking authorization boundary
 * and does not elevate an authenticated customer request to SYSTEM.
 * </p>
 */
public class BookingFacadePaymentQuoteAdapter
        implements TrustedBookingPaymentQuoteProvider {

    private final BookingFacade bookingFacade;

    /**
     * Creates the adapter using the public WP05 booking contract.
     *
     * @param bookingFacade authoritative booking facade
     */
    public BookingFacadePaymentQuoteAdapter(BookingFacade bookingFacade) {
        this.bookingFacade = Objects.requireNonNull(
                bookingFacade, "bookingFacade must not be null");
    }

    /**
     * Retrieves and validates the trusted payment quote for a booking.
     *
     * <p>
     * Booking authorization and resource lookup errors from WP05
     * are propagated without bypassing or replacing the original
     * security checks.
     * </p>
     *
     * @param bookingId verified booking identifier
     * @return validated server-side payment quote
     * @throws IllegalArgumentException when the booking ID is invalid
     * @throws IllegalStateException    when the trusted snapshot is invalid
     *                                  or the booking is not eligible for payment
     */
    @Override
    public BookingPaymentQuote getPaymentQuote(String bookingId) {

        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException(
                    "Booking ID must not be blank");
        }

        String normalizedBookingId = bookingId.trim();

        // Preserve the authenticated caller and WP05 authorization checks.
        BookingPaymentSnapshot snapshot = bookingFacade.getPaymentSnapshot(normalizedBookingId);

        validateSnapshot(normalizedBookingId, snapshot);

        // Map only authoritative server-side financial values.
        return new BookingPaymentQuote(
                snapshot.amount(),
                snapshot.currency(),
                snapshot.commissionRate(),
                snapshot.acceptedAt(),
                snapshot.paymentDueAt());
    }

    /**
     * Validates booking identity, payment eligibility and financial data
     * before exposing the snapshot to payment processing.
     *
     * @param bookingId requested booking identifier
     * @param snapshot  authoritative WP05 payment snapshot
     */
    private void validateSnapshot(
            String bookingId,
            BookingPaymentSnapshot snapshot) {

        if (snapshot == null) {
            throw new IllegalStateException(
                    "Trusted booking payment snapshot is unavailable");
        }

        if (!bookingId.equals(snapshot.bookingId())) {
            throw new IllegalStateException(
                    "Trusted booking payment snapshot identity mismatch");
        }

        if (snapshot.customerId() == null
                || snapshot.customerId().isBlank()) {
            throw new IllegalStateException(
                    "Trusted booking customer identity is unavailable");
        }

        if (snapshot.status() != BookingStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException(
                    "Booking is not awaiting payment");
        }

        validateAmount(snapshot.amount());
        validateCurrency(snapshot.currency());
        validateCommissionRate(snapshot.commissionRate());

        if (snapshot.priceCapturedAt() == null) {
            throw new IllegalStateException(
                    "Booking price capture timestamp is unavailable");
        }

        if (snapshot.acceptedAt() == null) {
            throw new IllegalStateException(
                    "Provider acceptance timestamp is unavailable");
        }

        if (snapshot.paymentDueAt() == null) {
            throw new IllegalStateException(
                    "Booking payment deadline is unavailable");
        }

        if (!snapshot.paymentDueAt().isAfter(snapshot.acceptedAt())) {
            throw new IllegalStateException(
                    "Booking payment deadline is invalid");
        }

        // Deadline enforcement remains the responsibility of the
        // payment lifecycle policy, not this snapshot adapter.
    }

    /**
     * Ensures that the authoritative payment amount is positive.
     *
     * @param amount booking payment amount
     */
    private void validateAmount(BigDecimal amount) {

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalStateException(
                    "Trusted booking payment amount must be positive");
        }
    }

    /**
     * Validates the ISO 4217 currency code without silently replacing
     * invalid financial information.
     *
     * @param currency booking payment currency
     */
    private void validateCurrency(String currency) {

        if (currency == null || currency.isBlank()) {
            throw new IllegalStateException(
                    "Trusted booking payment currency is unavailable");
        }

        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Trusted booking payment currency is invalid",
                    exception);
        }
    }

    /**
     * Ensures that the commission rate is a valid fraction between
     * zero and one, inclusive.
     *
     * @param commissionRate authoritative booking commission rate
     */
    private void validateCommissionRate(BigDecimal commissionRate) {

        if (commissionRate == null
                || commissionRate.compareTo(BigDecimal.ZERO) < 0
                || commissionRate.compareTo(BigDecimal.ONE) > 0) {

            throw new IllegalStateException(
                    "Trusted booking commission rate is invalid");
        }
    }
}