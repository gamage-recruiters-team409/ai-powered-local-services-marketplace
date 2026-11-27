package com.gamagerecruiters.marketplace.modules.payment.config;

import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.gateway.BookingFacadePaymentQuoteAdapter;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the trusted booking payment quote integration between
 * WP05 Booking and WP07 Payments.
 *
 * <p>
 * Payment processing obtains authoritative financial information
 * through the public booking facade instead of relying on
 * client-supplied values or direct booking repository access.
 * </p>
 */
@Configuration
public class BookingPaymentQuoteProviderConfiguration {

    /**
     * Registers the trusted booking payment quote adapter.
     *
     * <p>
     * The booking facade is a required dependency. Application
     * startup must fail if the required integration is unavailable,
     * rather than silently using an unavailable quote provider.
     * </p>
     *
     * @param bookingFacade authoritative WP05 booking facade
     * @return trusted booking payment quote provider
     */
    @Bean
    public TrustedBookingPaymentQuoteProvider bookingPaymentQuoteProvider(
            BookingFacade bookingFacade) {

        return new BookingFacadePaymentQuoteAdapter(bookingFacade);
    }
}