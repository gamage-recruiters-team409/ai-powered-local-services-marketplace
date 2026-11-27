package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway.RecoveredCheckoutSession;

/**
 * Verifies safe recovery of interrupted checkout reservations.
 * No external Stripe requests are performed by these tests.
 */
@ExtendWith(MockitoExtension.class)
class StripeCheckoutRecoveryServiceTest {

    private static final String PAYMENT_ID = "payment-1";
    private static final String TOKEN = "reservation-123";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String CURRENCY = "USD";

    private final Instant reservedAt =
            Instant.now().minusSeconds(600);

    @Mock
    private PaymentPersistenceService persistenceService;

    @Mock
    private PaymentCheckoutGateway checkoutGateway;

    @Mock
    private StripeCheckoutReservationService reservationService;

    @Mock
    private StripeCheckoutSessionPersistenceService sessionPersistenceService;

    private StripeCheckoutRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new StripeCheckoutRecoveryService(
                persistenceService,
                checkoutGateway,
                reservationService,
                sessionPersistenceService);
    }

    @Test
    void recoversExistingOpenSession() {

        preparePayment(true);

        stubLookup(Optional.of(openSession()));

        assertTrue(service.recoverCheckout(PAYMENT_ID));

        verify(sessionPersistenceService).attachSession(
                PAYMENT_ID, "cs_test_123", TOKEN);

        verifyNoInteractions(reservationService);
        verifyNoMoreInteractions(checkoutGateway);
    }

    @Test
    void recoversCompletedPaidSessionWithoutCheckoutUrl() {

        preparePayment(true);

        stubLookup(Optional.of(new RecoveredCheckoutSession(
                "cs_test_123", null, "complete", "paid")));

        assertTrue(service.recoverCheckout(PAYMENT_ID));

        verify(sessionPersistenceService).attachSession(
                PAYMENT_ID, "cs_test_123", TOKEN);

        verifyNoInteractions(reservationService);
        verifyNoMoreInteractions(checkoutGateway);
    }

    @Test
    void retainsSubmittedReservationWhenNoSessionIsFound() {

        preparePayment(true);

        stubLookup(Optional.empty());

        assertFalse(service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                reservationService, sessionPersistenceService);

        verifyNoMoreInteractions(checkoutGateway);
    }

    @Test
    void retainsReservationWhenGatewayLookupFails() {

        preparePayment(true);

        when(checkoutGateway.findCheckoutSession(
                PAYMENT_ID, AMOUNT, CURRENCY, reservedAt))
                .thenThrow(new IllegalStateException("Gateway timeout"));

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                reservationService, sessionPersistenceService);
    }

    @Test
    void releasesExplicitlyUnsubmittedReservationThroughAtomicGuard() {

        preparePayment(false);

        when(reservationService.releaseStaleUnsubmittedReservation(
                PAYMENT_ID, TOKEN)).thenReturn(true);

        assertFalse(service.recoverCheckout(PAYMENT_ID));

        verify(reservationService)
                .releaseStaleUnsubmittedReservation(PAYMENT_ID, TOKEN);

        verifyNoInteractions(
                checkoutGateway, sessionPersistenceService);
    }

    @Test
    void doesNotContactGatewayWhenStaleReleaseLosesRace() {

        preparePayment(false);

        when(reservationService.releaseStaleUnsubmittedReservation(
                PAYMENT_ID, TOKEN)).thenReturn(false);

        assertFalse(service.recoverCheckout(PAYMENT_ID));

        verify(reservationService)
                .releaseStaleUnsubmittedReservation(PAYMENT_ID, TOKEN);

        verifyNoInteractions(
                checkoutGateway, sessionPersistenceService);
    }

    @Test
    void reconcilesLegacyReservationWithoutAutomaticallyReleasingIt() {

        preparePayment(null);

        stubLookup(Optional.empty());

        assertFalse(service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                reservationService, sessionPersistenceService);

        verifyNoMoreInteractions(checkoutGateway);
    }

    @Test
    void returnsSuccessfullyWhenSessionWasAlreadyAttached() {

        Payment payment = payment(null);

        payment.setGatewayRef("cs_test_existing");
        payment.setCheckoutReservationToken(null);
        payment.setCheckoutReservedAt(null);

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertTrue(service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                checkoutGateway,
                reservationService,
                sessionPersistenceService);
    }

    @Test
    void rejectsInconsistentAttachedSessionState() {

        Payment payment = payment(true);
        payment.setGatewayRef("cs_test_existing");

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                checkoutGateway,
                reservationService,
                sessionPersistenceService);
    }

    @Test
    void rejectsRecoveryWithoutReservation() {

        Payment payment = payment(null);
        payment.setCheckoutReservationToken(null);

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                checkoutGateway,
                reservationService,
                sessionPersistenceService);
    }

    @Test
    void rejectsRecoveryForNonInitiatedPayment() {

        Payment payment = payment(true);
        payment.setStatus(PaymentStatus.CAPTURED);

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                checkoutGateway,
                reservationService,
                sessionPersistenceService);
    }

    @Test
    void rejectsInvalidRecoveredSession() {

        preparePayment(true);

        stubLookup(Optional.of(new RecoveredCheckoutSession(
                "cs_test_123", null, "open", "unpaid")));

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                reservationService, sessionPersistenceService);
    }

    @Test
    void doesNotReleaseReservationAfterAttachmentConflict() {

        preparePayment(true);

        stubLookup(Optional.of(openSession()));

        doThrow(new IllegalStateException("Concurrent update"))
                .when(sessionPersistenceService)
                .attachSession(PAYMENT_ID, "cs_test_123", TOKEN);

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(reservationService);
        verifyNoMoreInteractions(checkoutGateway);
    }

    @Test
    void rejectsRecoveryInsideActiveTransaction() {

        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        try {
            assertThrows(IllegalStateException.class,
                    () -> service.recoverCheckout(PAYMENT_ID));

            verifyNoInteractions(
                    persistenceService,
                    checkoutGateway,
                    reservationService,
                    sessionPersistenceService);
        } finally {
            TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }

    @Test
    void rejectsMissingPayment() {

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.recoverCheckout(PAYMENT_ID));

        verifyNoInteractions(
                checkoutGateway,
                reservationService,
                sessionPersistenceService);
    }

    private void preparePayment(Boolean submissionStarted) {

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment(submissionStarted)));
    }

    private void stubLookup(
            Optional<RecoveredCheckoutSession> result) {

        when(checkoutGateway.findCheckoutSession(
                PAYMENT_ID, AMOUNT, CURRENCY, reservedAt))
                .thenReturn(result);
    }

    private Payment payment(Boolean submissionStarted) {

        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId("booking-1");
        payment.setCustomerId("customer-1");
        payment.setAmount(AMOUNT);
        payment.setCurrency(CURRENCY);
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setCheckoutReservationToken(TOKEN);
        payment.setCheckoutReservedAt(reservedAt);
        payment.setCheckoutSubmissionStarted(submissionStarted);

        return payment;
    }

    private static RecoveredCheckoutSession openSession() {

        return new RecoveredCheckoutSession(
                "cs_test_123",
                "https://checkout.stripe.com/test",
                "open",
                "unpaid");
    }
}