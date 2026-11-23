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

        verify(mongoTemplate).updateFirst(
                any(Query.class),
                any(UpdateDefinition.class),
                eq(Payment.class));
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