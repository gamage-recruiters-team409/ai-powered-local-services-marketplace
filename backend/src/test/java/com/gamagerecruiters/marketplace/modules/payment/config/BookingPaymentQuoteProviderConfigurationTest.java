package com.gamagerecruiters.marketplace.modules.payment.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.gateway.BookingFacadePaymentQuoteAdapter;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Verifies explicit registration of the authoritative WP05
 * booking payment quote adapter.
 */
class BookingPaymentQuoteProviderConfigurationTest {

    /**
     * The payment module must expose exactly one trusted quote
     * provider backed by the public booking facade.
     */
    @Test
    void registersAuthoritativeBookingQuoteProvider() {

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {

            context.registerBean(
                    BookingFacade.class,
                    () -> mock(BookingFacade.class));

            context.register(
                    BookingPaymentQuoteProviderConfiguration.class);

            context.refresh();

            assertEquals(
                    1,
                    context.getBeansOfType(
                            TrustedBookingPaymentQuoteProvider.class).size());

            TrustedBookingPaymentQuoteProvider provider =
                    context.getBean(
                            TrustedBookingPaymentQuoteProvider.class);

            assertInstanceOf(
                    BookingFacadePaymentQuoteAdapter.class,
                    provider);
        }
    }
}
