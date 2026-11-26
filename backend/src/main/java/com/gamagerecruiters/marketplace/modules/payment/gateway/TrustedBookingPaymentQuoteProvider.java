package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provides authoritative booking payment information to WP07.
 *
 * <p>Implementations must obtain values from trusted server-side booking
 * data. Client-supplied amounts, currencies, commission rates, and
 * acceptance timestamps must never be treated as authoritative.</p>
 */
public interface TrustedBookingPaymentQuoteProvider {

    /**
     * Retrieves the immutable payment quote for a booking.
     *
     * @param bookingId verified booking identifier
     * @return trusted payment quote
     */
    BookingPaymentQuote getPaymentQuote(String bookingId);

    /**
     * Server-side booking payment snapshot.
     *
     * @param amount amount payable by the customer
     * @param currency ISO 4217 currency code
     * @param commissionRate marketplace commission fraction
     * @param providerAcceptedAt provider acceptance timestamp
     */
    record BookingPaymentQuote(
            BigDecimal amount,
            String currency,
            BigDecimal commissionRate,
            Instant providerAcceptedAt) {
    }
}