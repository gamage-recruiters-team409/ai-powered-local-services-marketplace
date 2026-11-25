package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.mongodb.client.result.UpdateResult;

/**
 * Unit tests for atomic Stripe Checkout reservation management.
 */
@ExtendWith(MockitoExtension.class)
class StripeCheckoutReservationServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private StripeCheckoutReservationService service;

    @BeforeEach
    void setUp() {
        service = new StripeCheckoutReservationService(mongoTemplate);
    }

    @Test
    void reservesEligiblePaymentWithUniqueToken() {
        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        String token = service.tryReserve("payment-1");

        assertNotNull(token);
        assertFalse(token.isBlank());

        ArgumentCaptor<Query> queryCaptor =
                ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor =
                ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(Payment.class));

        Document query = queryCaptor.getValue().getQueryObject();
        Document update = updateCaptor.getValue().getUpdateObject();
        Document set = (Document) update.get("$set");

        assertEquals("payment-1", query.getString("_id"));
        assertEquals(PaymentStatus.INITIATED, query.get("status"));
        assertNull(query.get("gatewayRef"));
        assertNull(query.get("checkoutReservationToken"));

        assertTrue(query.containsKey("gatewayPaymentIntentId"));
        assertNull(query.get("gatewayPaymentIntentId"));

        assertTrue(query.containsKey("checkoutReservedAt"));
        assertNull(query.get("checkoutReservedAt"));

        assertTrue(query.containsKey("checkoutSubmissionStarted"));
        assertNull(query.get("checkoutSubmissionStarted"));

        assertEquals(token, set.getString("checkoutReservationToken"));
        assertNotNull(set.get("checkoutReservedAt"));
        assertEquals(Boolean.FALSE,
                set.get("checkoutSubmissionStarted"));
        assertNotNull(set.get("updatedAt"));
    }

    @Test
    void rejectsReservationWhenConditionalUpdateDoesNotMatch() {
        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertNull(service.tryReserve("payment-1"));
    }

    @Test
    void releasesOnlyReservationWithMatchingToken() {
        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertTrue(service.releaseReservation(
                "payment-1", "reservation-123"));

        ArgumentCaptor<Query> queryCaptor =
                ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor =
                ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(Payment.class));

        Document query = queryCaptor.getValue().getQueryObject();
        Document update = updateCaptor.getValue().getUpdateObject();
        Document unset = (Document) update.get("$unset");

        assertEquals("payment-1", query.getString("_id"));
        assertEquals("reservation-123",
                query.getString("checkoutReservationToken"));
        assertEquals(PaymentStatus.INITIATED, query.get("status"));
        assertNull(query.get("gatewayRef"));

        assertTrue(query.containsKey("gatewayPaymentIntentId"));
        assertNull(query.get("gatewayPaymentIntentId"));

        assertEquals(Boolean.TRUE,
                query.get("checkoutSubmissionStarted"));

        assertTrue(unset.containsKey("checkoutReservationToken"));
        assertTrue(unset.containsKey("checkoutReservedAt"));
        assertTrue(unset.containsKey("checkoutSubmissionStarted"));
    }

    @Test
    void doesNotReleaseReservationWhenTokenDoesNotMatch() {
        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertFalse(service.releaseReservation(
                "payment-1", "incorrect-token"));
    }

    @Test
    void authorizesSubmissionOnlyForCurrentUnsubmittedReservation() {

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertTrue(service.markSubmissionStarted(
                "payment-1", "reservation-123"));

        ArgumentCaptor<Query> queryCaptor =
                ArgumentCaptor.forClass(Query.class);

        ArgumentCaptor<Update> updateCaptor =
                ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(Payment.class));

        Document query = queryCaptor.getValue().getQueryObject();

        Document set = (Document) updateCaptor.getValue()
                .getUpdateObject().get("$set");

        assertEquals("payment-1", query.getString("_id"));
        assertEquals(PaymentStatus.INITIATED, query.get("status"));
        assertEquals("reservation-123",
                query.getString("checkoutReservationToken"));

        assertEquals(Boolean.FALSE,
                query.get("checkoutSubmissionStarted"));

        assertTrue(query.containsKey("gatewayRef"));
        assertNull(query.get("gatewayRef"));

        assertTrue(query.containsKey("gatewayPaymentIntentId"));
        assertNull(query.get("gatewayPaymentIntentId"));

        assertEquals(Boolean.TRUE,
                set.get("checkoutSubmissionStarted"));
    }

    @Test
    void rejectsSubmissionWhenReservationWasChanged() {

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertFalse(service.markSubmissionStarted(
                "payment-1", "old-token"));
    }

    @Test
    void releasesOnlyStaleExplicitlyUnsubmittedReservation() {

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        java.time.Instant before = java.time.Instant.now();

        assertTrue(service.releaseStaleUnsubmittedReservation(
                "payment-1", "reservation-123"));

        java.time.Instant after = java.time.Instant.now();

        ArgumentCaptor<Query> queryCaptor =
                ArgumentCaptor.forClass(Query.class);

        ArgumentCaptor<Update> updateCaptor =
                ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(Payment.class));

        Document query = queryCaptor.getValue().getQueryObject();

        Document unset = (Document) updateCaptor.getValue()
                .getUpdateObject().get("$unset");

        assertEquals("payment-1", query.getString("_id"));
        assertEquals(PaymentStatus.INITIATED, query.get("status"));
        assertEquals("reservation-123",
                query.getString("checkoutReservationToken"));

        assertEquals(Boolean.FALSE,
                query.get("checkoutSubmissionStarted"));

        assertTrue(query.containsKey("gatewayRef"));
        assertNull(query.get("gatewayRef"));

        assertTrue(query.containsKey("gatewayPaymentIntentId"));
        assertNull(query.get("gatewayPaymentIntentId"));

        Document ageCondition =
                (Document) query.get("checkoutReservedAt");

        java.time.Instant cutoff =
                (java.time.Instant) ageCondition.get("$lte");

        assertFalse(cutoff.isBefore(before.minusSeconds(300)));
        assertFalse(cutoff.isAfter(after.minusSeconds(300)));

        assertTrue(unset.containsKey("checkoutReservationToken"));
        assertTrue(unset.containsKey("checkoutReservedAt"));
        assertTrue(unset.containsKey("checkoutSubmissionStarted"));
    }

    @Test
    void retainsReservationWhenStaleReleaseConditionDoesNotMatch() {

        when(mongoTemplate.updateFirst(
                any(Query.class), any(Update.class), eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertFalse(service.releaseStaleUnsubmittedReservation(
                "payment-1", "reservation-123"));
    }

    @Test
    void rejectsInvalidSubmissionAndRecoveryArguments() {

        assertThrows(IllegalArgumentException.class, () ->
                service.markSubmissionStarted(null, "token"));

        assertThrows(IllegalArgumentException.class, () ->
                service.markSubmissionStarted("payment-1", " "));

        assertThrows(IllegalArgumentException.class, () ->
                service.releaseStaleUnsubmittedReservation(
                        " ", "token"));

        assertThrows(IllegalArgumentException.class, () ->
                service.releaseStaleUnsubmittedReservation(
                        "payment-1", null));

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void rejectsInvalidReservationArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> service.tryReserve(" "));

        assertThrows(IllegalArgumentException.class,
                () -> service.tryReserve(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.releaseReservation("payment-1", " "));

        assertThrows(IllegalArgumentException.class,
                () -> service.releaseReservation(null, "token"));

        verifyNoInteractions(mongoTemplate);
    }
}