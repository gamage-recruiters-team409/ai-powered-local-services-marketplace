package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provides authoritative booking financial information required
 * for secure payment initiation.
 *
 * <p>
 * Payment values and deadlines must originate from the trusted
 * booking module rather than customer-supplied request data.
 * </p>
 */
public interface TrustedBookingPaymentQuoteProvider {

    /**
     * Retrieves the trusted payment quote for an eligible booking.
     *
     * @param bookingId the booking identifier
     * @return authoritative booking payment quote
     */
    BookingPaymentQuote getPaymentQuote(String bookingId);

    /**
     * Immutable financial and timing snapshot used during payment
     * initiation.
     *
     * @param amount             authoritative booking payment amount
     * @param currency           authoritative booking currency
     * @param commissionRate     captured booking commission rate
     * @param providerAcceptedAt provider acceptance timestamp
     * @param paymentDueAt       authoritative booking payment deadline
     */
    record BookingPaymentQuote(
            BigDecimal amount,
            String currency,
            BigDecimal commissionRate,
            Instant providerAcceptedAt,
            Instant paymentDueAt) {
    }
}