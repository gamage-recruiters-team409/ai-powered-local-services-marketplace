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

import com.gamagerecruiters.marketplace.modules.payment.config.StripeCheckoutRedirectPolicy;
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
                reservationService,
                new StripeCheckoutRedirectPolicy(
                        SUCCESS_URL, CANCEL_URL));
    }

    @Test
    void rejectsExternalSuccessUrlBeforePaymentReservation() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        "https://evil.example/success", CANCEL_URL));

        verifyNoCheckoutInteractions();
    }

    @Test
    void rejectsExternalCancelUrlBeforePaymentReservation() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, "https://evil.example/cancel"));

        verifyNoCheckoutInteractions();
    }

    @Test
    void rejectsMisleadingRedirectHostname() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        "https://example.com.evil.example/success",
                        CANCEL_URL));

        verifyNoCheckoutInteractions();
    }

    @Test
    void rejectsMalformedRedirectUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        "https://example.com/invalid path",
                        CANCEL_URL));

        verifyNoCheckoutInteractions();
    }

    @Test
    void rejectsUnapprovedPathOnTrustedHost() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        "https://example.com/unapproved",
                        CANCEL_URL));

        verifyNoCheckoutInteractions();
    }

    @Test
    void rejectsRedirectContainingUserInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        "https://example.com@evil.example/success",
                        CANCEL_URL));

        verifyNoCheckoutInteractions();
    }

    @Test
    void rejectsCheckoutWhenTrustedRedirectsAreNotConfigured() {
        StripeCheckoutInitiationService unconfiguredService =
                new StripeCheckoutInitiationService(
                        authorizationService,
                        persistenceService,
                        quoteProvider,
                        checkoutGateway,
                        sessionPersistenceService,
                        reservationService,
                        new StripeCheckoutRedirectPolicy("", ""));

        assertThrows(IllegalStateException.class,
                () -> unconfiguredService.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoCheckoutInteractions();
    }

    /**
     * Invalid redirects must not trigger payment processing,
     * financial reservations or external gateway requests.
     */
    private void verifyNoCheckoutInteractions() {
        verifyNoInteractions(
                authorizationService,
                persistenceService,
                quoteProvider,
                reservationService,
                checkoutGateway,
                sessionPersistenceService);
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

        when(reservationService.markSubmissionStarted(
                PAYMENT_ID, "reservation-123"))
                .thenReturn(true);

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
    /**
     * A confirmed pre-request failure must release the newly acquired
     * reservation so that checkout can be retried safely.
     */
    @Test
    void releasesReservationWhenGatewayRequestWasNotSubmitted() {
        prepareReservedCheckout();

        when(checkoutGateway.createCheckoutSession(
                PAYMENT_ID, new BigDecimal("100.00"), "USD",
                SUCCESS_URL, CANCEL_URL))
                .thenThrow(new com.gamagerecruiters.marketplace.modules.payment.gateway
                        .CheckoutNotSubmittedException("Stripe gateway disabled"));

        when(reservationService.releaseReservation(
                PAYMENT_ID, "reservation-123"))
                .thenReturn(true);

        assertThrows(
                com.gamagerecruiters.marketplace.modules.payment.gateway
                        .CheckoutNotSubmittedException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL));

        verify(reservationService).releaseReservation(
                PAYMENT_ID, "reservation-123");

        verifyNoInteractions(sessionPersistenceService);
    }

    /**
     * An uncertain gateway outcome must not permit another checkout claim.
     */
    @Test
    void retainsReservationAfterAmbiguousGatewayFailure() {
        prepareReservedCheckout();

        when(checkoutGateway.createCheckoutSession(
                PAYMENT_ID, new BigDecimal("100.00"), "USD",
                SUCCESS_URL, CANCEL_URL))
                .thenThrow(new IllegalStateException("Gateway timeout"));

        assertThrows(IllegalStateException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL));

        verify(reservationService, never())
                .releaseReservation(anyString(), anyString());

        verifyNoInteractions(sessionPersistenceService);
    }

    /**
     * An incomplete response does not prove that no session was created.
     */
    @Test
    void retainsReservationAfterIncompleteGatewayResponse() {
        prepareReservedCheckout();

        when(checkoutGateway.createCheckoutSession(
                PAYMENT_ID, new BigDecimal("100.00"), "USD",
                SUCCESS_URL, CANCEL_URL))
                .thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL));

        verify(reservationService, never())
                .releaseReservation(anyString(), anyString());

        verifyNoInteractions(sessionPersistenceService);
    }

    /**
     * A session may already exist when local persistence fails.
     * The reservation must remain available for controlled recovery.
     */
    @Test
    void retainsReservationWhenSessionPersistenceFails() {
        prepareReservedCheckout();

        when(checkoutGateway.createCheckoutSession(
                PAYMENT_ID, new BigDecimal("100.00"), "USD",
                SUCCESS_URL, CANCEL_URL))
                .thenReturn(new PaymentCheckoutGateway.CheckoutSession(
                        "cs_test_123", "https://checkout.stripe.com/test"));

        org.mockito.Mockito.doThrow(
                new IllegalStateException("Session persistence failed"))
                .when(sessionPersistenceService)
                .attachSession(
                        PAYMENT_ID, "cs_test_123", "reservation-123");

        assertThrows(IllegalStateException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL));

        verify(reservationService, never())
                .releaseReservation(anyString(), anyString());

        verify(sessionPersistenceService).attachSession(
                PAYMENT_ID, "cs_test_123", "reservation-123");
    }

    @Test
    void rejectsCheckoutWhenSubmissionAuthorizationFails() {

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(trustedQuote());

        when(reservationService.tryReserve(PAYMENT_ID))
                .thenReturn("reservation-123");

        when(reservationService.markSubmissionStarted(
                PAYMENT_ID, "reservation-123"))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () ->
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID,
                        SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(
                checkoutGateway, sessionPersistenceService);

        verify(reservationService, never())
                .releaseReservation(anyString(), anyString());
    }

    @Test
    void rejectsCheckoutInsideActiveTransaction() {

        org.springframework.transaction.support
                .TransactionSynchronizationManager
                .setActualTransactionActive(true);

        try {
            assertThrows(IllegalStateException.class, () ->
                    service.initiate(
                            PAYMENT_ID, CUSTOMER_ID,
                            SUCCESS_URL, CANCEL_URL));

            verifyNoInteractions(
                    persistenceService,
                    reservationService,
                    checkoutGateway,
                    sessionPersistenceService);
        } finally {
            org.springframework.transaction.support
                    .TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }

    /**
     * Unsupported currency must not leave an active checkout reservation.
     */
    @Test
    void releasesReservationAfterUnsupportedCurrencyValidation() {
        verifyMonetaryValidationFailure(
                new BigDecimal("100.00"), "AUD");
    }

    /**
     * An amount that cannot be represented in minor units must be released.
     */
    @Test
    void releasesReservationAfterInvalidMinorUnitValidation() {
        verifyMonetaryValidationFailure(
                new BigDecimal("10.001"), "USD");
    }

    private void verifyMonetaryValidationFailure(
            BigDecimal amount, String currency) {

        Payment invalidPayment = payment();
        invalidPayment.setAmount(amount);
        invalidPayment.setCurrency(currency);

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(invalidPayment));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        amount, currency, new BigDecimal("0.10"),
                        Instant.now().minusSeconds(60)));

        when(reservationService.tryReserve(PAYMENT_ID))
                .thenReturn("invalid-reservation");

        when(reservationService.markSubmissionStarted(
                PAYMENT_ID, "invalid-reservation"))
                .thenReturn(true);

        // Exercise the actual gateway's deterministic monetary validation.
        PaymentCheckoutGateway realGateway =
                new com.gamagerecruiters.marketplace.modules.payment.gateway
                        .StripeCheckoutGateway(
                        new com.gamagerecruiters.marketplace.modules.payment.config
                                .StripeGatewayProperties(
                                        true, "sk_test_dummy", "whsec_dummy"));

        StripeCheckoutInitiationService validationService =
                new StripeCheckoutInitiationService(
                        authorizationService,
                        persistenceService,
                        quoteProvider,
                        realGateway,
                        sessionPersistenceService,
                        reservationService,
                        new StripeCheckoutRedirectPolicy(
                                SUCCESS_URL, CANCEL_URL));

        when(reservationService.releaseReservation(
                PAYMENT_ID, "invalid-reservation"))
                .thenReturn(true);

        assertThrows(
                com.gamagerecruiters.marketplace.modules.payment.gateway
                        .CheckoutNotSubmittedException.class,
                () -> validationService.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL));

        verify(reservationService).releaseReservation(
                PAYMENT_ID, "invalid-reservation");

        verifyNoInteractions(sessionPersistenceService);
    }

    /**
     * Corrected matching payment data can acquire a fresh reservation
     * after a confirmed pre-submission failure.
     */
    @Test
    void permitsSuccessfulRetryAfterMonetaryValidationCorrection() {
        Payment invalidPayment = payment();
        invalidPayment.setCurrency("AUD");

        Payment correctedPayment = payment();

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(
                        Optional.of(invalidPayment),
                        Optional.of(correctedPayment));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(
                        new BookingPaymentQuote(
                                new BigDecimal("100.00"),
                                "AUD",
                                new BigDecimal("0.10"),
                                Instant.now().minusSeconds(60)),
                        trustedQuote());

        when(reservationService.tryReserve(PAYMENT_ID))
                .thenReturn("invalid-reservation", "retry-reservation");

        when(reservationService.markSubmissionStarted(
                PAYMENT_ID, "invalid-reservation"))
                .thenReturn(true);

        when(reservationService.markSubmissionStarted(
                PAYMENT_ID, "retry-reservation"))
                .thenReturn(true);

        when(checkoutGateway.createCheckoutSession(
                PAYMENT_ID, new BigDecimal("100.00"), "AUD",
                SUCCESS_URL, CANCEL_URL))
                .thenThrow(
                        new com.gamagerecruiters.marketplace.modules.payment.gateway
                                .CheckoutNotSubmittedException(
                                        "Unsupported Stripe checkout currency."));

        when(reservationService.releaseReservation(
                PAYMENT_ID, "invalid-reservation"))
                .thenReturn(true);

        assertThrows(
                com.gamagerecruiters.marketplace.modules.payment.gateway
                        .CheckoutNotSubmittedException.class,
                () -> service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL));

        verifyNoInteractions(sessionPersistenceService);

        when(checkoutGateway.createCheckoutSession(
                PAYMENT_ID, new BigDecimal("100.00"), "USD",
                SUCCESS_URL, CANCEL_URL))
                .thenReturn(new PaymentCheckoutGateway.CheckoutSession(
                        "cs_test_retry",
                        "https://checkout.stripe.com/retry"));

        PaymentCheckoutGateway.CheckoutSession result =
                service.initiate(
                        PAYMENT_ID, CUSTOMER_ID, SUCCESS_URL, CANCEL_URL);

        assertEquals("cs_test_retry", result.sessionId());

        org.mockito.InOrder ordered =
                org.mockito.Mockito.inOrder(
                        reservationService, sessionPersistenceService);

        ordered.verify(reservationService).tryReserve(PAYMENT_ID);
        ordered.verify(reservationService).markSubmissionStarted(
                PAYMENT_ID, "invalid-reservation");
        ordered.verify(reservationService).releaseReservation(
                PAYMENT_ID, "invalid-reservation");
        ordered.verify(reservationService).tryReserve(PAYMENT_ID);
        ordered.verify(reservationService).markSubmissionStarted(
                PAYMENT_ID, "retry-reservation");
        ordered.verify(sessionPersistenceService).attachSession(
                PAYMENT_ID, "cs_test_retry", "retry-reservation");

        verify(reservationService, never()).releaseReservation(
                PAYMENT_ID, "retry-reservation");
    }

    private void prepareReservedCheckout() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(trustedQuote());

        when(reservationService.tryReserve(PAYMENT_ID))
                .thenReturn("reservation-123");

        when(reservationService.markSubmissionStarted(
                PAYMENT_ID, "reservation-123"))
                .thenReturn(true);
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