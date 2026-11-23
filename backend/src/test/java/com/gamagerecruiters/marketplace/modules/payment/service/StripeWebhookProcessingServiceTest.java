package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.gateway.StripeWebhookVerifier;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;

/**
 * Unit tests for authenticated Stripe webhook processing.
 *
 * <p>All Stripe events and downstream services are mocked.
 * No network, MongoDB, or Docker connection is required.</p>
 */
class StripeWebhookProcessingServiceTest {

    private StripeWebhookVerifier verifier;
    private StripeWebhookEventRegistrationService registrationService;
    private StripeWebhookTransactionService transactionService;
    private StripeWebhookProcessingService service;

    @BeforeEach
    void setUp() {
        verifier = mock(StripeWebhookVerifier.class);
        registrationService =
                mock(StripeWebhookEventRegistrationService.class);
        transactionService =
                mock(StripeWebhookTransactionService.class);

        service = new StripeWebhookProcessingService(
                verifier, registrationService, transactionService);
    }

    private Event mockEvent(String type, Session session)
            throws Exception {

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_test_123");
        when(event.getType()).thenReturn(type);

        if (session != null) {
            EventDataObjectDeserializer deserializer =
                    mock(EventDataObjectDeserializer.class);

            when(deserializer.getObject())
                    .thenReturn(Optional.of(session));

            when(event.getDataObjectDeserializer())
                    .thenReturn(deserializer);
        }

        when(verifier.verify(anyString(), anyString()))
                .thenReturn(event);

        return event;
    }

    private Session paidSession() {
        Session session = mock(Session.class);

        when(session.getPaymentStatus()).thenReturn("paid");
        when(session.getId()).thenReturn("cs_test_123");
        when(session.getClientReferenceId()).thenReturn("pay_123");
        when(session.getCurrency()).thenReturn("usd");
        when(session.getAmountTotal()).thenReturn(10000L);

        return session;
    }

    @Test
    void validPaidSessionIsRegisteredAndCaptured() throws Exception {
        mockEvent("checkout.session.completed", paidSession());

        when(transactionService.captureVerifiedPayment(
                anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString()))
                .thenReturn(true);

        boolean result = service.process("raw-payload", "signature");

        assertTrue(result);

        verify(registrationService).register(any(Event.class));

        verify(transactionService).captureVerifiedPayment(
                eq("evt_test_123"),
                eq("pay_123"),
                eq("cs_test_123"),
                eq(new BigDecimal("100.00")),
                eq("USD"));
    }

    @Test
    void unsupportedEventTypeIsIgnored() throws Exception {
        mockEvent("customer.created", null);

        assertFalse(service.process("raw-payload", "signature"));

        verifyNoInteractions(registrationService, transactionService);
    }

    @Test
    void unpaidCheckoutSessionIsNotCaptured() throws Exception {
        Session session = paidSession();
        when(session.getPaymentStatus()).thenReturn("unpaid");

        mockEvent("checkout.session.completed", session);

        assertFalse(service.process("raw-payload", "signature"));

        verifyNoInteractions(registrationService, transactionService);
    }

    @Test
    void missingPaymentReferenceIsRejected() throws Exception {
        Session session = paidSession();
        when(session.getClientReferenceId()).thenReturn(null);

        mockEvent("checkout.session.completed", session);

        assertThrows(IllegalStateException.class,
                () -> service.process("raw-payload", "signature"));

        verifyNoInteractions(registrationService, transactionService);
    }

    @Test
    void missingAmountIsRejected() throws Exception {
        Session session = paidSession();
        when(session.getAmountTotal()).thenReturn(null);

        mockEvent("checkout.session.completed", session);

        assertThrows(IllegalStateException.class,
                () -> service.process("raw-payload", "signature"));

        verifyNoInteractions(registrationService, transactionService);
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        Session session = paidSession();
        when(session.getCurrency()).thenReturn("JPY");

        mockEvent("checkout.session.completed", session);

        assertThrows(IllegalStateException.class,
                () -> service.process("raw-payload", "signature"));

        verifyNoInteractions(registrationService, transactionService);
    }

    @Test
    void asynchronousSuccessfulPaymentIsCaptured() throws Exception {
        mockEvent(
                "checkout.session.async_payment_succeeded",
                paidSession());

        when(transactionService.captureVerifiedPayment(
                anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString()))
                .thenReturn(true);

        assertTrue(service.process("raw-payload", "signature"));

        verify(registrationService).register(any(Event.class));
        verify(transactionService).captureVerifiedPayment(
                anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString());
    }

    @Test
    void invalidSignatureStopsProcessing() throws Exception {
        when(verifier.verify(anyString(), anyString()))
                .thenThrow(new SignatureVerificationException(
                        "Invalid signature", "sig_header"));

        assertThrows(SignatureVerificationException.class,
                () -> service.process("raw-payload", "signature"));

        verifyNoInteractions(registrationService, transactionService);
    }
}