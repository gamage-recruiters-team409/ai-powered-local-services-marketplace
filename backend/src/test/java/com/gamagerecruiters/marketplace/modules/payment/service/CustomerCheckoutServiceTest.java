package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeCheckoutRedirectPolicy;
import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway.CheckoutSession;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway.RecoveredCheckoutSession;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerCheckoutService.CheckoutState;

/**
 * Verifies secure customer Checkout orchestration.
 *
 * <p>
 * Existing payment creation, authorization, gateway initiation
 * and recovery boundaries are isolated to verify coordination
 * without external financial operations.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CustomerCheckoutServiceTest {

    private static final String BOOKING_ID = "booking-001";
    private static final String CUSTOMER_ID = "customer-001";
    private static final String PAYMENT_ID = "payment-001";
    private static final String SESSION_ID = "cs_test_session_001";

    private static final String CURRENCY = "LKR";

    private static final BigDecimal AMOUNT = new BigDecimal("10000.00");

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.10");

    private static final String SUCCESS_URL = "https://example.com/payment/success";

    private static final String CANCEL_URL = "https://example.com/payment/cancel";

    private static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/cs_test_session_001";

    @Mock
    private BookingPaymentCreationService creationService;

    @Mock
    private PaymentBookingAuthorizationService authorizationService;

    @Mock
    private TrustedBookingPaymentQuoteProvider quoteProvider;

    @Mock
    private StripeCheckoutInitiationService initiationService;

    @Mock
    private StripeCheckoutRecoveryService recoveryService;

    @Mock
    private PaymentCheckoutGateway checkoutGateway;

    @Mock
    private PaymentRepository paymentRepository;

    private CustomerCheckoutService service;

    @BeforeEach
    void setUp() {

        service = buildService(
                true,
                SUCCESS_URL,
                CANCEL_URL);
    }

    /**
     * First Checkout request must use trusted server redirects
     * and verify persisted session state before returning a URL.
     */
    @Test
    void createsNewCheckoutUsingServerControlledRedirects() {

        Payment payment = validPayment();

        stubEligiblePayment(payment);

        when(initiationService.initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL))
                .thenReturn(new CheckoutSession(
                        SESSION_ID,
                        CHECKOUT_URL));

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(attachedPayment()));

        var result = checkout();

        assertEquals(PAYMENT_ID, result.paymentId());
        assertEquals(CheckoutState.CHECKOUT_READY, result.state());
        assertEquals(CHECKOUT_URL, result.checkoutUrl());

        verify(creationService).createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        verify(initiationService).initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL);

        verify(paymentRepository).findById(PAYMENT_ID);

        verifyNoInteractions(
                recoveryService,
                checkoutGateway);
    }

    @Test
    void normalizesBookingAndCustomerIdentifiers() {

        stubEligiblePayment(attachedPayment());
        stubOpenSession();

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(attachedPayment()));

        var result = service.checkout(
                " " + BOOKING_ID + " ",
                " " + CUSTOMER_ID + " ");

        assertEquals(CheckoutState.CHECKOUT_READY, result.state());

        verify(creationService).createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        verifyNoInteractions(initiationService, recoveryService);
    }

    /**
     * An existing open session must be reused instead of
     * creating another external Checkout Session.
     */
    @Test
    void reusesExistingOpenSession() {

        stubEligiblePayment(attachedPayment());
        stubOpenSession();

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(attachedPayment()));

        var result = checkout();

        assertEquals(PAYMENT_ID, result.paymentId());
        assertEquals(CheckoutState.CHECKOUT_READY, result.state());
        assertEquals(CHECKOUT_URL, result.checkoutUrl());

        verify(checkoutGateway).retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY);

        verifyNoInteractions(
                initiationService,
                recoveryService);
    }

    /**
     * Completed sessions are not treated as authoritative
     * payment capture and must not expose another Checkout URL.
     */
    @ParameterizedTest
    @ValueSource(strings = { "paid", "unpaid" })
    void completedSessionReturnsVerificationPending(
            String paymentStatus) {

        stubEligiblePayment(attachedPayment());

        when(checkoutGateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY))
                .thenReturn(new RecoveredCheckoutSession(
                        SESSION_ID,
                        null,
                        "complete",
                        paymentStatus));

        var result = checkout();

        assertEquals(PAYMENT_ID, result.paymentId());

        assertEquals(
                CheckoutState.PAYMENT_VERIFICATION_PENDING,
                result.state());

        assertNull(result.checkoutUrl());

        verifyNoInteractions(
                initiationService,
                recoveryService);
    }

    /**
     * Interrupted submissions must use the existing recovery
     * mechanism before attempting to retrieve a session.
     */
    @Test
    void recoversInterruptedCheckoutWithoutReplacement() {

        stubEligiblePayment(reservedPayment());

        when(recoveryService.recoverCheckout(PAYMENT_ID))
                .thenReturn(true);

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(attachedPayment()));

        stubOpenSession();

        var result = checkout();

        assertEquals(CheckoutState.CHECKOUT_READY, result.state());
        assertEquals(CHECKOUT_URL, result.checkoutUrl());

        verify(recoveryService).recoverCheckout(PAYMENT_ID);

        verify(checkoutGateway).retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY);

        verifyNoInteractions(initiationService);
    }

    @Test
    void unresolvedRecoveryDoesNotCreateReplacementSession() {

        stubEligiblePayment(reservedPayment());

        when(recoveryService.recoverCheckout(PAYMENT_ID))
                .thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verify(recoveryService).recoverCheckout(PAYMENT_ID);

        verifyNoInteractions(
                initiationService,
                checkoutGateway,
                paymentRepository);
    }

    @Test
    void recoveryFailureDoesNotCreateReplacementSession() {

        stubEligiblePayment(reservedPayment());

        when(recoveryService.recoverCheckout(PAYMENT_ID))
                .thenThrow(new IllegalStateException(
                        "Gateway recovery unavailable"));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                initiationService,
                checkoutGateway,
                paymentRepository);
    }

    @Test
    void rejectsRecoveredPaymentWithoutAttachedSession() {

        stubEligiblePayment(reservedPayment());

        when(recoveryService.recoverCheckout(PAYMENT_ID))
                .thenReturn(true);

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(validPayment()));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                initiationService,
                checkoutGateway);
    }

    @Test
    void rejectsAttachedSessionWithUnresolvedReservation() {

        Payment payment = reservedPayment();
        payment.setGatewayRef(SESSION_ID);

        stubEligiblePayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsUnauthorizedCustomerBeforePaymentCreation() {

        doThrow(new AccessDeniedException("Access denied"))
                .when(authorizationService)
                .requirePaymentInitiationStatus(
                        BOOKING_ID, CUSTOMER_ID);

        assertThrows(
                AccessDeniedException.class,
                this::checkout);

        verifyNoInteractions(
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsIneligibleBookingBeforePaymentCreation() {

        doThrow(new IllegalStateException(
                "Booking is not awaiting payment"))
                .when(authorizationService)
                .requirePaymentInitiationStatus(
                        BOOKING_ID, CUSTOMER_ID);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsMismatchedPaymentCustomer() {

        Payment payment = validPayment();
        payment.setCustomerId("different-customer");

        stubPayment(payment);

        assertThrows(
                AccessDeniedException.class,
                this::checkout);

        verifyNoInteractions(quoteProvider);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsMismatchedPaymentBooking() {

        Payment payment = validPayment();
        payment.setBookingId("different-booking");

        stubPayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsMissingPayment() {

        stubPayment(null);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsMissingPaymentIdentifier() {

        Payment payment = validPayment();
        payment.setId(null);

        stubPayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AUTHORIZED",
            "CAPTURED",
            "RELEASED",
            "REFUND_PENDING",
            "PARTIALLY_REFUNDED",
            "REFUNDED",
            "FAILED"
    })
    void rejectsNonInitiatedPayment(String status) {

        Payment payment = validPayment();
        payment.setStatus(PaymentStatus.valueOf(status));

        stubPayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsPaymentWithExistingPaymentIntent() {

        Payment payment = validPayment();
        payment.setGatewayPaymentIntentId("pi_existing");

        stubPayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsTrustedAmountMismatch() {

        Payment payment = validPayment();
        payment.setAmount(new BigDecimal("9999.00"));

        stubEligiblePayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsTrustedCurrencyMismatch() {

        Payment payment = validPayment();
        payment.setCurrency("USD");

        stubEligiblePayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsTrustedCommissionMismatch() {

        Payment payment = validPayment();

        payment.setCommissionRate(
                new BigDecimal("0.20"));

        stubEligiblePayment(payment);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsMissingTrustedQuote() {

        stubPayment(validPayment());

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsExpiredAuthoritativeDeadline() {

        stubPayment(validPayment());

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(expiredQuote());

        assertThrows(
                IllegalArgumentException.class,
                this::checkout);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsDeadlineExpirationDuringSessionRetrieval() {

        stubPayment(attachedPayment());

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(
                        validQuote(),
                        validQuote(),
                        expiredQuote());

        stubOpenSession();

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(attachedPayment()));

        assertThrows(
                IllegalArgumentException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsDeadlineExpirationDuringNewCheckout() {

        stubPayment(validPayment());

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(
                        validQuote(),
                        expiredQuote());

        when(initiationService.initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL))
                .thenReturn(new CheckoutSession(
                        SESSION_ID,
                        CHECKOUT_URL));

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(attachedPayment()));

        assertThrows(
                IllegalArgumentException.class,
                this::checkout);

        verifyNoInteractions(
                recoveryService,
                checkoutGateway);
    }

    @Test
    void rejectsPaymentStateChangeDuringSessionRetrieval() {

        stubEligiblePayment(attachedPayment());
        stubOpenSession();

        Payment capturedPayment = attachedPayment();
        capturedPayment.setStatus(PaymentStatus.CAPTURED);

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(capturedPayment));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsGatewayReferenceChangeDuringRetrieval() {

        stubEligiblePayment(attachedPayment());
        stubOpenSession();

        Payment changedPayment = attachedPayment();
        changedPayment.setGatewayRef("cs_different_session");

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(changedPayment));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsIncorrectRetrievedSessionIdentity() {

        stubEligiblePayment(attachedPayment());

        when(checkoutGateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY))
                .thenReturn(new RecoveredCheckoutSession(
                        "cs_different_session",
                        CHECKOUT_URL,
                        "open",
                        "unpaid"));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsExpiredRetrievedSession() {

        stubEligiblePayment(attachedPayment());

        when(checkoutGateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY))
                .thenReturn(new RecoveredCheckoutSession(
                        SESSION_ID,
                        null,
                        "expired",
                        "unpaid"));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsOpenSessionWithoutCheckoutUrl() {

        stubEligiblePayment(attachedPayment());

        when(checkoutGateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY))
                .thenReturn(new RecoveredCheckoutSession(
                        SESSION_ID,
                        null,
                        "open",
                        "unpaid"));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsNullRetrievedSession() {

        stubEligiblePayment(attachedPayment());

        when(checkoutGateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(initiationService);
    }

    @Test
    void rejectsNullInitiationResponse() {

        stubEligiblePayment(validPayment());

        when(initiationService.initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                paymentRepository,
                recoveryService,
                checkoutGateway);
    }

    @Test
    void rejectsNewSessionWithMissingCheckoutUrl() {

        stubEligiblePayment(validPayment());

        when(initiationService.initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL))
                .thenReturn(new CheckoutSession(
                        SESSION_ID,
                        null));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsNewSessionWithoutPersistedPayment() {

        stubEligiblePayment(validPayment());
        stubSuccessfulInitiation();

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                this::checkout);
    }

    @Test
    void rejectsNewSessionWithIncorrectPersistedReference() {

        stubEligiblePayment(validPayment());
        stubSuccessfulInitiation();

        Payment payment = attachedPayment();
        payment.setGatewayRef("cs_incorrect_reference");

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                this::checkout);
    }

    @Test
    void rejectsNewSessionWithUnresolvedPersistedReservation() {

        stubEligiblePayment(validPayment());
        stubSuccessfulInitiation();

        Payment payment = reservedPayment();
        payment.setGatewayRef(SESSION_ID);

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                this::checkout);
    }

    @Test
    void initiationFailureDoesNotTriggerReplacementOrRecovery() {

        stubEligiblePayment(validPayment());

        when(initiationService.initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL))
                .thenThrow(new IllegalStateException(
                        "Ambiguous gateway submission"));

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verify(initiationService, times(1)).initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL);

        verifyNoInteractions(
                recoveryService,
                checkoutGateway,
                paymentRepository);
    }

    @Test
    void disabledGatewayRejectsBeforePaymentCreation() {

        service = buildService(
                false,
                SUCCESS_URL,
                CANCEL_URL);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @Test
    void missingSuccessRedirectRejectsBeforePaymentCreation() {

        service = buildService(
                true,
                "",
                CANCEL_URL);

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @Test
    void missingCancelRedirectRejectsBeforePaymentCreation() {

        service = buildService(
                true,
                SUCCESS_URL,
                "");

        assertThrows(
                IllegalStateException.class,
                this::checkout);

        verifyNoInteractions(
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void rejectsInvalidBookingIdentifier(String bookingId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(
                        bookingId,
                        CUSTOMER_ID));

        verifyNoInteractions(
                authorizationService,
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void rejectsInvalidCustomerIdentifier(String customerId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.checkout(
                        BOOKING_ID,
                        customerId));

        verifyNoInteractions(
                authorizationService,
                creationService,
                quoteProvider);

        verifyNoGatewayOperations();
    }

    @Test
    void rejectsExecutionInsideActiveTransaction() {

        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        try {

            assertThrows(
                    IllegalStateException.class,
                    this::checkout);

            verifyNoInteractions(
                    authorizationService,
                    creationService,
                    quoteProvider);

            verifyNoGatewayOperations();

        } finally {

            TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }

    @Test
    void rejectsExecutionInsideActiveSynchronization() {

        TransactionSynchronizationManager.initSynchronization();

        try {

            assertThrows(
                    IllegalStateException.class,
                    this::checkout);

            verifyNoInteractions(
                    authorizationService,
                    creationService,
                    quoteProvider);

            verifyNoGatewayOperations();

        } finally {

            TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    // Test fixtures and reusable verification helpers.

    private CustomerCheckoutService buildService(
            boolean enabled,
            String successUrl,
            String cancelUrl) {

        return new CustomerCheckoutService(
                creationService,
                authorizationService,
                quoteProvider,
                initiationService,
                recoveryService,
                checkoutGateway,
                paymentRepository,
                new StripeCheckoutRedirectPolicy(
                        successUrl,
                        cancelUrl),
                new StripeGatewayProperties(
                        enabled,
                        "sk_test_unit",
                        "whsec_unit"));
    }

    private Payment validPayment() {

        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setCustomerId(CUSTOMER_ID);
        payment.setAmount(AMOUNT);
        payment.setCurrency(CURRENCY);
        payment.setCommissionRate(COMMISSION_RATE);

        payment.setCommissionAmount(
                new BigDecimal("1000.00"));

        payment.setRefundedAmount(
                new BigDecimal("0.00"));

        payment.setStatus(PaymentStatus.INITIATED);

        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());

        return payment;
    }

    private Payment attachedPayment() {

        Payment payment = validPayment();

        payment.setGatewayRef(SESSION_ID);

        return payment;
    }

    private Payment reservedPayment() {

        Payment payment = validPayment();

        payment.setCheckoutReservationToken(
                "existing-reservation-token");

        payment.setCheckoutReservedAt(
                Instant.now().minusSeconds(60));

        payment.setCheckoutSubmissionStarted(true);

        return payment;
    }

    private BookingPaymentQuote validQuote() {

        Instant now = Instant.now();

        return new BookingPaymentQuote(
                AMOUNT,
                CURRENCY,
                COMMISSION_RATE,
                now.minusSeconds(60),
                now.plusSeconds(840));
    }

    private BookingPaymentQuote expiredQuote() {

        Instant now = Instant.now();

        return new BookingPaymentQuote(
                AMOUNT,
                CURRENCY,
                COMMISSION_RATE,
                now.minusSeconds(1200),
                now.minusSeconds(300));
    }

    private void stubPayment(Payment payment) {

        when(creationService.createOrGetPayment(
                BOOKING_ID,
                CUSTOMER_ID))
                .thenReturn(payment);
    }

    private void stubEligiblePayment(Payment payment) {

        stubPayment(payment);

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(validQuote());
    }

    private void stubOpenSession() {

        when(checkoutGateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY))
                .thenReturn(new RecoveredCheckoutSession(
                        SESSION_ID,
                        CHECKOUT_URL,
                        "open",
                        "unpaid"));
    }

    private void stubSuccessfulInitiation() {

        when(initiationService.initiate(
                PAYMENT_ID,
                CUSTOMER_ID,
                SUCCESS_URL,
                CANCEL_URL))
                .thenReturn(new CheckoutSession(
                        SESSION_ID,
                        CHECKOUT_URL));
    }

    private CustomerCheckoutService.CheckoutResult checkout() {

        return service.checkout(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    /**
     * Invalid requests must never reach external financial
     * submission or recovery operations.
     */
    private void verifyNoGatewayOperations() {

        verifyNoInteractions(
                initiationService,
                recoveryService,
                checkoutGateway,
                paymentRepository);
    }
}