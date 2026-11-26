
package com.gamagerecruiters.marketplace.modules.payment.initializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

/**
 * Unit tests for MongoDB payment index initialization.
 *
 * <p>
 * Uses Mockito to verify index creation, validation, and
 * protection against incompatible index configurations
 * without connecting to a live MongoDB database.
 * </p>
 *
 * <p>
 * Includes regression coverage for the previously approved
 * booking and gateway indexes, as well as the customer payment
 * history compound index required by the WP01 integration.
 * </p>
 */
class PaymentIndexInitializerTest {

        private MongoTemplate mongoTemplate;
        private MongoCollection<Document> collection;
        private PaymentIndexInitializer initializer;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() {
                mongoTemplate = mock(MongoTemplate.class);
                collection = mock(MongoCollection.class);

                when(mongoTemplate.collectionExists("payments"))
                                .thenReturn(true);

                when(mongoTemplate.getCollection("payments"))
                                .thenReturn(collection);

                initializer = new PaymentIndexInitializer(mongoTemplate);
        }

        /**
         * Verifies that a valid non-unique booking index
         * is accepted without creating a duplicate index.
         */
        @Test
        void shouldAcceptExistingBookingIndex() {
                mockIndexes(bookingIndex());

                initializer.ensureIndex(
                                "bookingId", "bookingId_1", false, false);

                verify(collection, never())
                                .createIndex(any(Bson.class), any(IndexOptions.class));
        }

        /**
         * Verifies that an existing partial unique gateway index
         * matches the required payment index configuration.
         */
        @Test
        void shouldAcceptExistingGatewayIndex() {
                mockIndexes(gatewayIndex());

                initializer.ensureIndex(
                                "gatewayRef", "gatewayRef_1", true, true);

                verify(collection, never())
                                .createIndex(any(Bson.class), any(IndexOptions.class));
        }

        /**
         * Ensures that a gateway index without uniqueness
         * is rejected to protect transaction references.
         */
        @Test
        void shouldRejectNonUniqueGatewayIndex() {
                Document index = new Document("name", "gatewayRef_1")
                                .append("key", new Document("gatewayRef", 1));

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayRef", "gatewayRef_1", true, true));
        }

        /**
         * Ensures that a gateway index missing its partial
         * filter expression is rejected.
         */
        @Test
        void shouldRejectGatewayIndexWithoutPartialFilter() {
                Document index = new Document("name", "gatewayRef_1")
                                .append("key", new Document("gatewayRef", 1))
                                .append("unique", true);

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayRef", "gatewayRef_1", true, true));
        }

        /**
         * Ensures that incompatible financial indexes
         * are never automatically deleted.
         */
        @Test
        void shouldNotDropIncompatibleIndexes() {
                Document index = new Document("name", "gatewayRef_1")
                                .append("key", new Document("gatewayRef", 1));

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayRef", "gatewayRef_1", true, true));

                verify(collection, never()).dropIndex(anyString());
        }

        /**
         * Verifies that a missing booking index is created
         * and validated after creation.
         */
        @Test
        void shouldCreateMissingBookingIndex() {
                mockIndexCreation(bookingIndex());

                initializer.ensureIndex(
                                "bookingId", "bookingId_1", false, false);

                ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

                verify(collection, times(1))
                                .createIndex(any(Bson.class), optionsCaptor.capture());

                IndexOptions options = optionsCaptor.getValue();

                assertFalse(Boolean.TRUE.equals(options.isUnique()));
                assertEquals("bookingId_1", options.getName());
        }

        /**
         * Verifies that a missing gateway index is created
         * with uniqueness and a string-only partial filter.
         */
        @Test
        void shouldCreateMissingGatewayIndex() {
                mockIndexCreation(gatewayIndex());

                initializer.ensureIndex(
                                "gatewayRef", "gatewayRef_1", true, true);

                ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

                verify(collection, times(1))
                                .createIndex(any(Bson.class), optionsCaptor.capture());

                IndexOptions options = optionsCaptor.getValue();

                assertTrue(Boolean.TRUE.equals(options.isUnique()));
                assertEquals("gatewayRef_1", options.getName());

                assertEquals(
                                new Document(
                                                "gatewayRef",
                                                new Document("$type", "string")),
                                options.getPartialFilterExpression());
        }

        /**
         * Verifies that an existing correctly configured
         * customer history index is accepted.
         */
        @Test
        void shouldAcceptExistingCustomerHistoryIndex() {
                mockIndexes(customerHistoryIndex());

                initializer.ensureCustomerHistoryIndex();

                verify(collection, never())
                                .createIndex(any(Bson.class), any(IndexOptions.class));
        }

        /**
         * Verifies that a missing customer history index
         * is created with the correct field order and directions.
         */
        @Test
        void shouldCreateMissingCustomerHistoryIndex() {
                mockIndexCreation(customerHistoryIndex());

                initializer.ensureCustomerHistoryIndex();

                ArgumentCaptor<Bson> keyCaptor = ArgumentCaptor.forClass(Bson.class);

                ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

                verify(collection, times(1))
                                .createIndex(keyCaptor.capture(), optionsCaptor.capture());

                // Convert both index definitions to BSON for an exact comparison.
                // This verifies the field names, field order, and sort directions.
                org.bson.BsonDocument actualKey = keyCaptor.getValue()
                                .toBsonDocument(
                                                Document.class,
                                                com.mongodb.MongoClientSettings
                                                                .getDefaultCodecRegistry());

                org.bson.BsonDocument expectedKey = customerHistoryKey()
                                .toBsonDocument(
                                                Document.class,
                                                com.mongodb.MongoClientSettings
                                                                .getDefaultCodecRegistry());

                assertEquals(expectedKey, actualKey);

                IndexOptions options = optionsCaptor.getValue();

                assertEquals(
                                PaymentIndexInitializer.CUSTOMER_HISTORY_INDEX,
                                options.getName());

                assertFalse(Boolean.TRUE.equals(options.isUnique()));
        }

        /**
         * Rejects a compound index when its fields
         * appear in the wrong order.
         */
        @Test
        void shouldRejectCustomerHistoryIndexWithWrongFieldOrder() {
                Document wrongKey = new Document("createdAt", -1)
                                .append("customerId", 1)
                                .append("_id", -1);

                mockIndexes(new Document(
                                "name",
                                PaymentIndexInitializer.CUSTOMER_HISTORY_INDEX)
                                .append("key", wrongKey));

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureCustomerHistoryIndex());
        }

        /**
         * Rejects a compound index when the creation timestamp
         * uses ascending rather than descending order.
         */
        @Test
        void shouldRejectCustomerHistoryIndexWithWrongSortDirection() {
                Document wrongKey = new Document("customerId", 1)
                                .append("createdAt", 1)
                                .append("_id", -1);

                mockIndexes(new Document(
                                "name",
                                PaymentIndexInitializer.CUSTOMER_HISTORY_INDEX)
                                .append("key", wrongKey));

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureCustomerHistoryIndex());
        }

        /**
         * Rejects a unique customer history index because
         * customers must be able to have multiple payments.
         */
        @Test
        void shouldRejectUniqueCustomerHistoryIndex() {
                Document index = customerHistoryIndex()
                                .append("unique", true);

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureCustomerHistoryIndex());
        }

        /**
         * Verifies that incompatible customer history indexes
         * are never automatically dropped.
         */
        @Test
        void shouldNotDropIncompatibleCustomerHistoryIndex() {
                Document index = customerHistoryIndex()
                                .append("unique", true);

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureCustomerHistoryIndex());

                verify(collection, never()).dropIndex(anyString());
        }

        /**
         * Creates metadata for a valid non-unique booking index.
         */
        private Document bookingIndex() {
                return new Document("name", "bookingId_1")
                                .append("key", new Document("bookingId", 1));
        }

        /**
         * Creates metadata for a valid partial unique gateway index.
         */
        private Document gatewayIndex() {
                return new Document("name", "gatewayRef_1")
                                .append("key", new Document("gatewayRef", 1))
                                .append("unique", true)
                                .append(
                                                "partialFilterExpression",
                                                new Document(
                                                                "gatewayRef",
                                                                new Document("$type", "string")));
        }

        /**
         * Creates the expected customer history compound index key.
         *
         * <p>
         * Field order is significant for MongoDB compound indexes.
         * </p>
         */
        private Document customerHistoryKey() {
                return new Document("customerId", 1)
                                .append("createdAt", -1)
                                .append("_id", -1);
        }

        /**
         * Creates metadata for a valid non-unique
         * customer history compound index.
         */
        private Document customerHistoryIndex() {
                return new Document(
                                "name",
                                PaymentIndexInitializer.CUSTOMER_HISTORY_INDEX)
                                .append("key", customerHistoryKey());
        }

        /**
         * Mocks an existing MongoDB index listing.
         *
         * @param indexes indexes returned by the mocked database
         */
        @SuppressWarnings("unchecked")
        private void mockIndexes(Document... indexes) {
                ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);

                when(collection.listIndexes()).thenReturn(iterable);

                when(iterable.into(anyCollection()))
                                .thenAnswer(invocation -> {
                                        Collection<Document> destination = invocation.getArgument(0);

                                        destination.addAll(List.of(indexes));
                                        return destination;
                                });
        }

        /**
         * Simulates MongoDB index creation by returning an empty
         * index list first and the newly created index afterward.
         *
         * @param createdIndex metadata of the newly created index
         */
        @SuppressWarnings("unchecked")
        private void mockIndexCreation(Document createdIndex) {
                ListIndexesIterable<Document> beforeCreation = mock(ListIndexesIterable.class);

                ListIndexesIterable<Document> afterCreation = mock(ListIndexesIterable.class);

                when(collection.listIndexes())
                                .thenReturn(beforeCreation, afterCreation);

                when(beforeCreation.into(anyCollection()))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(afterCreation.into(anyCollection()))
                                .thenAnswer(invocation -> {
                                        Collection<Document> destination = invocation.getArgument(0);

                                        destination.add(createdIndex);
                                        return destination;
                                });
        }

        /**
         * Verifies that an existing compatible PaymentIntent index
         * is accepted without unnecessary index creation.
         */
        @Test
        void shouldAcceptExistingPaymentIntentIndex() {
                mockIndexes(paymentIntentIndex());

                initializer.ensureIndex(
                                "gatewayPaymentIntentId",
                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                true, true);

                verify(collection, never())
                                .createIndex(any(Bson.class), any(IndexOptions.class));
        }

        /**
         * Verifies the exact index key, uniqueness, name,
         * and string-only partial filter used during creation.
         */
        @Test
        void shouldCreateMissingPaymentIntentIndex() {
                mockIndexCreation(paymentIntentIndex());

                initializer.ensureIndex(
                                "gatewayPaymentIntentId",
                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                true, true);

                ArgumentCaptor<Bson> keyCaptor =
                                ArgumentCaptor.forClass(Bson.class);

                ArgumentCaptor<IndexOptions> optionsCaptor =
                                ArgumentCaptor.forClass(IndexOptions.class);

                verify(collection, times(1))
                                .createIndex(
                                                keyCaptor.capture(),
                                                optionsCaptor.capture());

                org.bson.BsonDocument actualKey =
                                keyCaptor.getValue().toBsonDocument(
                                                Document.class,
                                                com.mongodb.MongoClientSettings
                                                                .getDefaultCodecRegistry());

                org.bson.BsonDocument expectedKey =
                                new Document("gatewayPaymentIntentId", 1)
                                                .toBsonDocument(
                                                                Document.class,
                                                                com.mongodb.MongoClientSettings
                                                                                .getDefaultCodecRegistry());

                assertEquals(expectedKey, actualKey);

                IndexOptions options = optionsCaptor.getValue();

                assertEquals(
                                "gatewayPaymentIntentId_1",
                                options.getName());

                assertTrue(Boolean.TRUE.equals(options.isUnique()));

                assertEquals(
                                new Document(
                                                "gatewayPaymentIntentId",
                                                new Document("$type", "string")),
                                options.getPartialFilterExpression());
        }

        @Test
        void shouldRejectNonUniquePaymentIntentIndex() {
                Document index = paymentIntentIndex()
                                .append("unique", false);

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayPaymentIntentId",
                                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                                true, true));
        }

        @Test
        void shouldRejectPaymentIntentIndexWithoutPartialFilter() {
                Document index = paymentIntentIndex();
                index.remove("partialFilterExpression");

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayPaymentIntentId",
                                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                                true, true));
        }

        @Test
        void shouldRejectPaymentIntentIndexWithIncorrectPartialFilter() {
                Document index = paymentIntentIndex()
                                .append(
                                                "partialFilterExpression",
                                                new Document(
                                                                "gatewayPaymentIntentId",
                                                                new Document("$exists", true)));

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayPaymentIntentId",
                                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                                true, true));
        }

        @Test
        void shouldRejectPaymentIntentIndexWithIncorrectKey() {
                Document index = paymentIntentIndex()
                                .append("key",
                                                new Document("gatewayRef", 1));

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayPaymentIntentId",
                                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                                true, true));
        }

        @Test
        void shouldRejectPaymentIntentIndexWithIncorrectSortDirection() {
                Document index = paymentIntentIndex()
                                .append("key",
                                                new Document("gatewayPaymentIntentId", -1));

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayPaymentIntentId",
                                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                                true, true));
        }

        /**
         * Incompatible financial indexes require explicit migration.
         * They must never be automatically dropped or replaced.
         */
        @Test
        void shouldNotDropIncompatiblePaymentIntentIndex() {
                Document index = paymentIntentIndex()
                                .append("unique", false);

                mockIndexes(index);

                assertThrows(
                                IllegalStateException.class,
                                () -> initializer.ensureIndex(
                                                "gatewayPaymentIntentId",
                                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX,
                                                true, true));

                verify(collection, never()).dropIndex(anyString());

                verify(collection, never())
                                .createIndex(any(Bson.class), any(IndexOptions.class));
        }

        /**
         * Creates metadata for a valid partial unique
         * Stripe PaymentIntent index.
         */
        private Document paymentIntentIndex() {
                return new Document(
                                "name",
                                PaymentIndexInitializer.GATEWAY_PAYMENT_INTENT_INDEX)
                                .append("key",
                                                new Document("gatewayPaymentIntentId", 1))
                                .append("unique", true)
                                .append(
                                                "partialFilterExpression",
                                                new Document(
                                                                "gatewayPaymentIntentId",
                                                                new Document("$type", "string")));
        }
}
