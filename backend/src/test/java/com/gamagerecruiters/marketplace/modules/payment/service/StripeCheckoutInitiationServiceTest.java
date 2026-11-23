package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;

@ExtendWith(MockitoExtension.class)
class StripeCheckoutInitiationServiceTest {

    private static final String PAYMENT_ID = "payment-1";
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "customer-1";
    private static final String SUCCESS_URL = "https://example.com/success";
    private static final String CANCEL_URL = "https://example.com/cancel";

    @Mock
    private PaymentBookingAuthorizationService authorizationService;

    @Mock
    private PaymentPersistenceService persistenceService;

    @Mock
    private TrustedBookingPaymentQuoteProvider quoteProvider;

    @Mock
    private PaymentCheckoutGateway checkoutGateway;

    @Mock
    private StripeCheckoutSessionPersistenceService sessionPersistenceService;

    @Mock
    private StripeCheckoutReservationService reservationService;

    private StripeCheckoutInitiationService service;

    @BeforeEach
    void setUp() {
        service = new StripeCheckoutInitiationService(
                authorizationService,
                persistenceService,
                quoteProvider,
                checkoutGateway,
                sessionPersistenceService,
                reservationService);
    }

    @Test
    void initiatesCheckoutUsingTrustedBookingQuote() {
        Payment payment = payment();
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));
        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(trustedQuote());
        when(reservationService.tryReserve(PAYMENT_ID))
                .thenReturn("reservation-123");

        PaymentCheckoutGateway.CheckoutSession session =
                new PaymentCheckoutGateway.CheckoutSession(
                        "cs_test_123", "https://checkout.stripe.com/test");

        when(checkoutGateway.createCheckoutSession(
                eq(PAYMENT_ID),
                eq(new BigDecimal("100.00")),
                eq("USD"),
                eq(SUCCESS_URL),
                eq(CANCEL_URL)))
                .thenReturn(session);

        PaymentCheckoutGateway.CheckoutSession result =
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL);

        assertEquals("cs_test_123", result.sessionId());

        verify(authorizationService).requirePaymentInitiationStatus(
                BOOKING_ID, CUSTOMER_ID);
        verify(sessionPersistenceService).attachSession(
                PAYMENT_ID, "cs_test_123", "reservation-123");
    }

    @Test
    void rejectsCheckoutForDifferentCustomer() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        assertThrows(AccessDeniedException.class, () ->
                service.initiate(
                        PAYMENT_ID, "another-customer",
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                authorizationService,
                quoteProvider,
                checkoutGateway,
                sessionPersistenceService);
    }

    @Test
    void rejectsPaymentThatIsAlreadyCaptured() {
        Payment payment = payment();
        payment.setStatus(PaymentStatus.CAPTURED);

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () ->
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                quoteProvider,
                checkoutGateway,
                sessionPersistenceService);
    }

    @Test
    void rejectsMismatchedTrustedAmount() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        new BigDecimal("200.00"),
                        "USD",
                        new BigDecimal("0.10"),
                        Instant.now().minusSeconds(60)));

        assertThrows(IllegalStateException.class, () ->
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                checkoutGateway,
                sessionPersistenceService);
    }

    @Test
    void rejectsExpiredPaymentDeadline() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        new BigDecimal("100.00"),
                        "USD",
                        new BigDecimal("0.10"),
                        Instant.now().minusSeconds(25 * 60 * 60)));

        assertThrows(IllegalArgumentException.class, () ->
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                checkoutGateway,
                sessionPersistenceService);
    }

    @Test
    void rejectsCheckoutWhenTrustedQuoteIsUnavailable() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenThrow(new IllegalStateException(
                        "Trusted booking quote unavailable"));

        assertThrows(IllegalStateException.class, () ->
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                checkoutGateway,
                sessionPersistenceService);
    }

    @Test
    void rejectsCheckoutWhenPaymentIsAlreadyReserved() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(trustedQuote());

        when(reservationService.tryReserve(PAYMENT_ID))
                .thenReturn(null);

        assertThrows(IllegalStateException.class, () ->
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                checkoutGateway,
                sessionPersistenceService);
    }
    private static Payment payment() {
        Payment payment = new Payment();
        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setCustomerId(CUSTOMER_ID);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("USD");
        payment.setCommissionRate(new BigDecimal("0.10"));
        payment.setStatus(PaymentStatus.INITIATED);
        return payment;
    }

    private static BookingPaymentQuote trustedQuote() {
        return new BookingPaymentQuote(
                new BigDecimal("100.00"),
                "USD",
                new BigDecimal("0.10"),
                Instant.now().minusSeconds(60));
    }
}