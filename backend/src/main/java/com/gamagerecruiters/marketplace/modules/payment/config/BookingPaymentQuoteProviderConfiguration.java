package com.gamagerecruiters.marketplace.modules.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.UnavailableBookingPaymentQuoteProvider;

/**
 * Registers a fail-closed booking quote provider until a trusted WP05
 * implementation becomes available.
 *
 * <p>The fallback is created only when no other trusted booking quote
 * provider bean has been registered.</p>
 */
@Configuration
public class BookingPaymentQuoteProviderConfiguration {

    /**
     * Provides the fail-closed fallback when WP05 integration is absent.
     *
     * @return fallback booking payment quote provider
     */
    @Bean
    @ConditionalOnMissingBean(TrustedBookingPaymentQuoteProvider.class)
    public TrustedBookingPaymentQuoteProvider bookingPaymentQuoteProvider() {
        return new UnavailableBookingPaymentQuoteProvider();
    }
}