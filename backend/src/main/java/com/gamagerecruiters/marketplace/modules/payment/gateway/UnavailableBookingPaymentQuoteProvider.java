package com.gamagerecruiters.marketplace.modules.payment.gateway;


/**
 * Fail-closed fallback until WP05 provides authoritative payment quotes.
 *
 * <p>This component never fabricates booking amounts, currencies,
 * commission rates, or provider acceptance timestamps. Checkout remains
 * unavailable until a trusted implementation is integrated.</p>
 */
public class UnavailableBookingPaymentQuoteProvider
        implements TrustedBookingPaymentQuoteProvider {

    /**
     * Rejects checkout initiation when trusted booking data is unavailable.
     *
     * @param bookingId booking identifier
     * @return never returns a payment quote
     * @throws IllegalStateException always, until integration is available
     */
    @Override
    public BookingPaymentQuote getPaymentQuote(String bookingId) {
        throw new IllegalStateException(
                "Stripe Checkout is unavailable: trusted WP05 booking "
                        + "payment details have not been integrated");
    }
}