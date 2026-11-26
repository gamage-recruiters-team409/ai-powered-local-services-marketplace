package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.UpdateResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent;

/**
 * Unit tests for atomic Stripe webhook capture orchestration.
 *
 * <p>MongoDB operations are mocked. These tests verify service decisions,
 * not actual MongoDB transaction atomicity or concurrency behavior.</p>
 */
class StripeWebhookTransactionServiceTest {

    private MongoTemplate mongoTemplate;
    private PlatformTransactionManager transactionManager;
    private StripeWebhookTransactionService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        transactionManager = mock(PlatformTransactionManager.class);

        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        when(transactionManager.getTransaction(any()))
                .thenReturn(transactionStatus);

        service = new StripeWebhookTransactionService(
                mongoTemplate, transactionManager);
    }

    private StripeWebhookEvent receivedEvent() {
        return new StripeWebhookEvent(
                "evt_test_123", "checkout.session.completed");
    }

    private Payment initiatedPayment() {
        Payment payment = new Payment();
        payment.setId("pay_123");
        payment.setGatewayRef("cs_test_123");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.INITIATED);
        return payment;
    }

    private void mockEventAndPayment(
            StripeWebhookEvent event, Payment payment) {

        when(mongoTemplate.findById(
                eq("evt_test_123"),
                eq(StripeWebhookEvent.class)))
                .thenReturn(event);

        when(mongoTemplate.findOne(
                any(Query.class), eq(Payment.class)))
                .thenReturn(payment);
    }

    private void mockSuccessfulUpdates() {
        com.mongodb.client.result.UpdateResult result =
                com.mongodb.client.result.UpdateResult
                        .acknowledged(1, 1L, null);

        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(StripeWebhookEvent.class)))
                .thenReturn(result);

        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(Payment.class)))
                .thenReturn(result);
    }

    @Test
    void successfulCaptureUpdatesPaymentAndEvent() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());
        mockSuccessfulUpdates();

        boolean captured = service.captureVerifiedPayment(
                "evt_test_123", "pay_123",
                "cs_test_123",
                "pi_test123",
                new BigDecimal("100.00"),
                "USD");

        assertTrue(captured);

        verify(mongoTemplate).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(Payment.class));

        verify(mongoTemplate, times(2)).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(StripeWebhookEvent.class));

        verify(transactionManager).commit(any());
    }

    @Test
    void alreadyProcessedEventIsNotCapturedAgain() {
        StripeWebhookEvent event = mock(StripeWebhookEvent.class);

        when(event.getStatus()).thenReturn(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);
        when(event.getPaymentId()).thenReturn("pay_123");
        when(event.getCheckoutSessionId()).thenReturn("cs_test_123");

        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setGatewayPaymentIntentId("pi_test123");

        mockEventAndPayment(event, payment);

        assertFalse(service.captureVerifiedPayment(
                "evt_test_123", "pay_123",
                "cs_test_123",
                "pi_test123",
                new BigDecimal("100.00"),
                "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));
    }

    @Test
    void unregisteredEventIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));

        verify(transactionManager).rollback(any());
    }

    @Test
    void amountMismatchIsRejected() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("99.00"), "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));
    }

    @Test
    void currencyMismatchIsRejected() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "EUR"));
    }

    @Test
    void invalidPaymentStatusIsRejected() {
        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.FAILED);

        mockEventAndPayment(receivedEvent(), payment);

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));
    }

    @Test
    void failedConditionalCaptureTriggersRollback() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());

        com.mongodb.client.result.UpdateResult success =
                com.mongodb.client.result.UpdateResult
                        .acknowledged(1, 1L, null);

        com.mongodb.client.result.UpdateResult failure =
                com.mongodb.client.result.UpdateResult
                        .acknowledged(0, 0L, null);

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class),
                eq(StripeWebhookEvent.class)))
                .thenReturn(success);

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class),
                eq(Payment.class)))
                .thenReturn(failure);

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));

        verify(transactionManager).rollback(any());
    }

    @Test
    void missingIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.captureVerifiedPayment(
                        " ", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));

        assertThrows(IllegalArgumentException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", null,
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));

        verifyNoInteractions(mongoTemplate);
    }

    /**
     * Rejects a Stripe payment reference that does not match
     * the persisted Payment ID.
     */
    @Test
    void paymentIdMismatchIsRejectedBeforeFinancialUpdates() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123",
                        "pay_different",
                        "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"),
                        "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                any(Class.class));

        verify(transactionManager).rollback(any());
    }

    /**
     * A different Stripe event for the same captured payment must
     * not trigger a second financial capture.
     */
    @Test
    void differentEventForLegacyCapturedPaymentDoesNotBackfillReceipt() {
        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setGatewayPaymentIntentId("pi_test123");

        mockEventAndPayment(receivedEvent(), payment);
        mockSuccessfulUpdates();

        boolean newlyCaptured = service.captureVerifiedPayment(
                "evt_test_123", "pay_123", "cs_test_123",
                "pi_test123",
                new BigDecimal("100.00"), "USD");

        assertFalse(newlyCaptured);

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class));

        verify(mongoTemplate, times(2)).updateFirst(
                any(Query.class), any(Update.class),
                eq(StripeWebhookEvent.class));

        verify(transactionManager).commit(any());
    }

    /**
     * A processed event cannot be reused with a different payment ID.
     */
    @Test
    void processedEventWithDifferentPaymentAssociationIsRejected() {
        StripeWebhookEvent event = mock(StripeWebhookEvent.class);

        when(event.getStatus()).thenReturn(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);
        when(event.getPaymentId()).thenReturn("pay_other");
        when(event.getCheckoutSessionId()).thenReturn("cs_test_123");

        mockEventAndPayment(event, initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager).rollback(any());
    }

    /**
     * Legacy processed events without a payment association
     * require reconciliation instead of silent acknowledgement.
     */
    @Test
    void processedEventWithoutAssociationIsRejected() {
        StripeWebhookEvent event = receivedEvent();
        event.setStatus(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);

        mockEventAndPayment(event, initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"), "USD"));

        verify(transactionManager).rollback(any());
    }

    @Test
    void processedEventWithDifferentAmountIsRejected() {
        StripeWebhookEvent event = mock(StripeWebhookEvent.class);

        when(event.getStatus()).thenReturn(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);
        when(event.getPaymentId()).thenReturn("pay_123");
        when(event.getCheckoutSessionId()).thenReturn("cs_test_123");

        mockEventAndPayment(event, initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123",
                        "pay_123",
                        "cs_test_123",
                        "pi_test123",
                        new BigDecimal("200.00"),
                        "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager).rollback(any());
    }

    @Test
    void processedEventWithDifferentCurrencyIsRejected() {
        StripeWebhookEvent event = mock(StripeWebhookEvent.class);

        when(event.getStatus()).thenReturn(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);
        when(event.getPaymentId()).thenReturn("pay_123");
        when(event.getCheckoutSessionId()).thenReturn("cs_test_123");

        mockEventAndPayment(event, initiatedPayment());

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123",
                        "pay_123",
                        "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"),
                        "EUR"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager).rollback(any());
    }

    /**
     * A concurrently claimed event must not trigger a payment update.
     */
    @Test
    void concurrentEventClaimFailureTriggersRollback() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());

        com.mongodb.client.result.UpdateResult failure =
                com.mongodb.client.result.UpdateResult
                        .acknowledged(0, 0L, null);

        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(StripeWebhookEvent.class)))
                .thenReturn(failure);

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123",
                        "pay_123",
                        "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"),
                        "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(Payment.class));

        verify(mongoTemplate, times(1)).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(StripeWebhookEvent.class));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * Event completion failure must roll back the capture transaction.
     */
    @Test
    void eventCompletionFailureTriggersRollback() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());

        com.mongodb.client.result.UpdateResult success =
                com.mongodb.client.result.UpdateResult
                        .acknowledged(1, 1L, null);

        com.mongodb.client.result.UpdateResult failure =
                com.mongodb.client.result.UpdateResult
                        .acknowledged(0, 0L, null);

        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(StripeWebhookEvent.class)))
                .thenReturn(success, failure);

        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(Payment.class)))
                .thenReturn(success);

        assertThrows(IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123",
                        "pay_123",
                        "cs_test_123",
                        "pi_test123",
                        new BigDecimal("100.00"),
                        "USD"));

        verify(mongoTemplate).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(Payment.class));

        verify(mongoTemplate, times(2)).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(StripeWebhookEvent.class));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * Verifies that the capture status and verified PaymentIntent
     * are included in the same conditional MongoDB update.
     */
    @Test
    void capturePersistsPaymentIntentAndReceiptAtomically() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());
        mockSuccessfulUpdates();

        assertTrue(service.captureVerifiedPayment(
                "evt_test_123", "pay_123", "cs_test_123",
                "pi_test123", new BigDecimal("100.00"), "USD"));

        org.mockito.ArgumentCaptor<Query> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(Query.class);

        org.mockito.ArgumentCaptor<Update> updateCaptor =
                org.mockito.ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(Payment.class));

        org.bson.Document filter =
                queryCaptor.getValue().getQueryObject();

        assertEquals("pay_123", filter.get("_id"));
        assertEquals(PaymentStatus.INITIATED, filter.get("status"));
        assertEquals("cs_test_123", filter.get("gatewayRef"));

        // The CAS filter must reject conflicting stored references.
        java.util.List<?> alternatives =
                (java.util.List<?>) filter.get("$or");

        assertNotNull(alternatives);
        assertEquals(2, alternatives.size());

        assertTrue(alternatives.contains(
                new org.bson.Document(
                        "gatewayPaymentIntentId", null)));

        assertTrue(alternatives.contains(
                new org.bson.Document(
                        "gatewayPaymentIntentId", "pi_test123")));

        org.bson.Document fields =
                (org.bson.Document) updateCaptor.getValue()
                        .getUpdateObject().get("$set");

        assertNotNull(fields);
        assertEquals(PaymentStatus.CAPTURED, fields.get("status"));
        assertEquals("pi_test123",
                fields.get("gatewayPaymentIntentId"));

        // Receipt metadata must be persisted with the verified capture.
        assertEquals("RCT-pay_123", fields.get("receiptReference"));
        assertInstanceOf(java.time.Instant.class, fields.get("paidAt"));
        assertEquals(fields.get("paidAt"), fields.get("updatedAt"));
        assertNotNull(fields.get("updatedAt"));

        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void missingPaymentIntentIsRejectedBeforeTransaction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        null, new BigDecimal("100.00"), "USD"));

        verifyNoInteractions(mongoTemplate, transactionManager);
    }

    @Test
    void blankPaymentIntentIsRejectedBeforeTransaction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "   ", new BigDecimal("100.00"), "USD"));

        verifyNoInteractions(mongoTemplate, transactionManager);
    }

    @Test
    void invalidPaymentIntentIsRejectedBeforeTransaction() {
        String[] invalidReferences = {
                "cs_test123",
                "ch_test123",
                "pi_",
                "pi_invalid-value",
                "pi_invalid value",
                "PI_test123"
        };

        for (String reference : invalidReferences) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.captureVerifiedPayment(
                            "evt_test_123", "pay_123", "cs_test_123",
                            reference,
                            new BigDecimal("100.00"), "USD"));

            assertEquals("Invalid Stripe PaymentIntent ID",
                    exception.getMessage());
        }

        verifyNoInteractions(mongoTemplate, transactionManager);
    }

    @Test
    void initiatedPaymentWithMatchingPaymentIntentCanBeCaptured() {
        Payment payment = initiatedPayment();
        payment.setGatewayPaymentIntentId("pi_test123");

        mockEventAndPayment(receivedEvent(), payment);
        mockSuccessfulUpdates();

        assertTrue(service.captureVerifiedPayment(
                "evt_test_123", "pay_123", "cs_test_123",
                "pi_test123", new BigDecimal("100.00"), "USD"));

        verify(mongoTemplate).updateFirst(
                any(Query.class), any(Update.class),
                eq(Payment.class));

        verify(transactionManager).commit(any());
    }

    @Test
    void initiatedPaymentWithConflictingPaymentIntentIsRejected() {
        Payment payment = initiatedPayment();
        payment.setGatewayPaymentIntentId("pi_other123");

        mockEventAndPayment(receivedEvent(), payment);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123", new BigDecimal("100.00"), "USD"));

        assertEquals("Stripe PaymentIntent reference mismatch",
                exception.getMessage());

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void capturedPaymentWithConflictingPaymentIntentIsRejected() {
        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setGatewayPaymentIntentId("pi_other123");

        mockEventAndPayment(receivedEvent(), payment);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123", new BigDecimal("100.00"), "USD"));

        assertEquals("Stripe PaymentIntent reference mismatch",
                exception.getMessage());

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void legacyCapturedPaymentRequiresReconciliation() {
        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);

        mockEventAndPayment(receivedEvent(), payment);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123", new BigDecimal("100.00"), "USD"));

        assertEquals(
                "Stored PaymentIntent reference is missing; "
                        + "verified reconciliation is required",
                exception.getMessage());

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * Processed events must not overwrite a conflicting reference
     * or silently backfill a missing legacy PaymentIntent.
     */
    @Test
    void processedEventRejectsMissingOrConflictingPaymentIntent() {
        StripeWebhookEvent event = mock(StripeWebhookEvent.class);

        when(event.getStatus()).thenReturn(
                StripeWebhookEvent.ProcessingStatus.PROCESSED);

        when(event.getPaymentId()).thenReturn("pay_123");
        when(event.getCheckoutSessionId()).thenReturn("cs_test_123");

        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);

        mockEventAndPayment(event, payment);

        IllegalStateException missing = assertThrows(
                IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123", new BigDecimal("100.00"), "USD"));

        assertEquals(
                "Stored PaymentIntent reference is missing; "
                        + "verified reconciliation is required",
                missing.getMessage());

        payment.setGatewayPaymentIntentId("pi_other123");

        IllegalStateException conflicting = assertThrows(
                IllegalStateException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123", new BigDecimal("100.00"), "USD"));

        assertEquals("Stripe PaymentIntent reference mismatch",
                conflicting.getMessage());

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), any(Class.class));

        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * Simulates a unique index violation during payment capture.
     * Verifies rollback orchestration, not real database rollback.
     */
    @Test
    void duplicatePaymentIntentKeyTriggersTransactionRollback() {
        mockEventAndPayment(receivedEvent(), initiatedPayment());
        mockSuccessfulUpdates();

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class),
                eq(Payment.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException(
                        "Duplicate gatewayPaymentIntentId"));

        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> service.captureVerifiedPayment(
                        "evt_test_123", "pay_123", "cs_test_123",
                        "pi_test123", new BigDecimal("100.00"), "USD"));

        verify(mongoTemplate, times(1)).updateFirst(
                any(Query.class), any(Update.class),
                eq(StripeWebhookEvent.class));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * A repeated capture event must not replace an issued receipt
     * or change the original verified capture timestamp.
     */
    @Test
    void repeatedCapturePreservesIssuedReceiptMetadata() {
        Payment payment = initiatedPayment();
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setGatewayPaymentIntentId("pi_test123");
        payment.setReceiptReference("RCT-pay_123");

        java.time.Instant originalPaidAt =
                java.time.Instant.parse("2026-11-26T10:00:00Z");

        payment.setPaidAt(originalPaidAt);

        mockEventAndPayment(receivedEvent(), payment);
        mockSuccessfulUpdates();

        assertFalse(service.captureVerifiedPayment(
                "evt_test_123", "pay_123", "cs_test_123",
                "pi_test123", new BigDecimal("100.00"), "USD"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(Payment.class));

        assertEquals("RCT-pay_123", payment.getReceiptReference());
        assertEquals(originalPaidAt, payment.getPaidAt());

        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }
}
