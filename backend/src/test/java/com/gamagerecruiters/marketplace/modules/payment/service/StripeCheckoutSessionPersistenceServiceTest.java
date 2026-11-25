package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class StripeCheckoutSessionPersistenceServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private StripeCheckoutSessionPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new StripeCheckoutSessionPersistenceService(mongoTemplate);
    }

    @Test
    void attachesSessionWhenConditionalUpdateSucceeds() {
        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(UpdateDefinition.class),
                eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertDoesNotThrow(() ->
                service.attachSession("payment-1", "cs_test_123", "reservation-123"));

        org.mockito.ArgumentCaptor<Query> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(Query.class);

        org.mockito.ArgumentCaptor<UpdateDefinition> updateCaptor =
                org.mockito.ArgumentCaptor.forClass(UpdateDefinition.class);

        verify(mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(Payment.class));

        org.bson.Document query =
                queryCaptor.getValue().getQueryObject();

        org.bson.Document update =
                updateCaptor.getValue().getUpdateObject();

        org.bson.Document unset =
                (org.bson.Document) update.get("$unset");

        org.bson.Document set =
                (org.bson.Document) update.get("$set");

        org.junit.jupiter.api.Assertions.assertEquals(
                "reservation-123",
                query.getString("checkoutReservationToken"));

        org.junit.jupiter.api.Assertions.assertEquals(
                "cs_test_123", set.getString("gatewayRef"));

        org.junit.jupiter.api.Assertions.assertTrue(
                query.containsKey("gatewayPaymentIntentId"));

        org.junit.jupiter.api.Assertions.assertNull(
                query.get("gatewayPaymentIntentId"));

        org.junit.jupiter.api.Assertions.assertEquals(
                new org.bson.Document("$ne", null),
                query.get("checkoutReservedAt"));

        org.junit.jupiter.api.Assertions.assertEquals(
                new org.bson.Document("$in",
                        java.util.Arrays.asList(Boolean.TRUE, null)),
                query.get("checkoutSubmissionStarted"));

        org.junit.jupiter.api.Assertions.assertTrue(
                unset.containsKey("checkoutReservationToken"));

        org.junit.jupiter.api.Assertions.assertTrue(
                unset.containsKey("checkoutReservedAt"));

        org.junit.jupiter.api.Assertions.assertTrue(
                unset.containsKey("checkoutSubmissionStarted"));
    }

    @Test
    void rejectsSessionWhenConditionalUpdateDoesNotModifyPayment() {
        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(UpdateDefinition.class),
                eq(Payment.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThrows(IllegalStateException.class, () ->
                service.attachSession("payment-1", "cs_test_123", "reservation-123"));
    }

    @Test
    void rejectsBlankSessionIdWithoutDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () ->
                service.attachSession("payment-1", " ", "reservation-123"));

        verifyNoInteractions(mongoTemplate);
    }
}