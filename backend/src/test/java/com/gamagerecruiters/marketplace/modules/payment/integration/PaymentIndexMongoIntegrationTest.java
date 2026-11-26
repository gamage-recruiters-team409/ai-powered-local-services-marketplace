
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.gamagerecruiters.marketplace.modules.payment.initializer.PaymentIndexInitializer;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Tests payment indexes against an isolated local MongoDB database.
 *
 * <p>
 * Each test uses a uniquely named temporary database on localhost.
 * These tests never connect to Atlas or the application's configured
 * database. Only the temporary test database is dropped.
 * </p>
 *
 * <p>
 * Covers the previously approved booking and gateway indexes,
 * together with the customer history compound index required
 * for the WP01 Customer Payments List integration.
 * </p>
 */
class PaymentIndexMongoIntegrationTest {

        private static final String LOCAL_URI = "mongodb://127.0.0.1:27017";

        private static final String CUSTOMER_HISTORY_INDEX = "customerId_1_createdAt_-1__id_-1";

        /**
         * Verifies creation, repeated initialization, and enforcement
         * of the required payment indexes against local MongoDB.
         */
        @Test
        void createsAndEnforcesPaymentIndexes() {
                String databaseName = "wp07_index_test_"
                                + UUID.randomUUID().toString().replace("-", "");

                try (MongoClient client = MongoClients.create(LOCAL_URI)) {

                        MongoTemplate template = new MongoTemplate(
                                        new SimpleMongoClientDatabaseFactory(
                                                        client, databaseName));

                        MongoCollection<Document> payments = client.getDatabase(databaseName)
                                        .getCollection("payments");

                        try {
                                PaymentIndexInitializer initializer = new PaymentIndexInitializer(template);

                                // Initialization must be safe to repeat.
                                initializer.initializeIndexes();
                                initializer.initializeIndexes();

                                List<Document> indexes = payments.listIndexes().into(new ArrayList<>());

                                Document bookingIndex = findIndex(indexes, "bookingId_1");

                                Document gatewayIndex = findIndex(indexes, "gatewayRef_1");

                                Document customerHistoryIndex = findIndex(indexes, CUSTOMER_HISTORY_INDEX);

                                assertNotNull(bookingIndex);
                                assertNotNull(gatewayIndex);
                                assertNotNull(customerHistoryIndex);

                                // Verify the Stripe PaymentIntent index.
                                Document paymentIntentIndex = findIndex(
                                                indexes,
                                                "gatewayPaymentIntentId_1");

                                assertNotNull(paymentIntentIndex);

                                assertEquals(
                                                new Document("gatewayPaymentIntentId", 1),
                                                paymentIntentIndex.get("key", Document.class));

                                assertEquals(
                                                Boolean.TRUE,
                                                paymentIntentIndex.get("unique"));

                                assertEquals(
                                                new Document(
                                                                "gatewayPaymentIntentId",
                                                                new Document("$type", "string")),
                                                paymentIntentIndex.get(
                                                                "partialFilterExpression",
                                                                Document.class));

                                // Booking IDs are non-unique because retries are allowed.
                                assertEquals(
                                                new Document("bookingId", 1),
                                                bookingIndex.get("key", Document.class));

                                assertNotEquals(
                                                Boolean.TRUE,
                                                bookingIndex.get("unique"));

                                // Gateway references must be unique when present.
                                assertEquals(
                                                new Document("gatewayRef", 1),
                                                gatewayIndex.get("key", Document.class));

                                assertEquals(
                                                Boolean.TRUE,
                                                gatewayIndex.get("unique"));

                                Document partialFilter = gatewayIndex.get(
                                                "partialFilterExpression",
                                                Document.class);

                                assertNotNull(partialFilter);
                                assertTrue(partialFilter.containsKey("gatewayRef"));

                                // Verify the exact customer history index field order.
                                Document expectedCustomerKey = new Document("customerId", 1)
                                                .append("createdAt", -1)
                                                .append("_id", -1);

                                assertEquals(
                                                expectedCustomerKey,
                                                customerHistoryIndex.get("key", Document.class));

                                assertNotEquals(
                                                Boolean.TRUE,
                                                customerHistoryIndex.get("unique"));

                                // Same booking may have multiple payment attempts.
                                payments.insertOne(new Document("_id", "payment-1")
                                                .append("bookingId", "booking-1")
                                                .append("customerId", "customer-1")
                                                .append("createdAt", Instant.parse(
                                                                "2026-11-16T10:00:00Z"))
                                                .append("gatewayRef", "gateway-1")
                                                .append("gatewayPaymentIntentId", "pi_test001"));

                                payments.insertOne(new Document("_id", "payment-2")
                                                .append("bookingId", "booking-1")
                                                .append("customerId", "customer-1")
                                                .append("createdAt", Instant.parse(
                                                                "2026-11-16T11:00:00Z"))
                                                .append("gatewayRef", "gateway-2")
                                                .append("gatewayPaymentIntentId", "pi_test002"));

                                // Missing gatewayRef must be allowed more than once.
                                payments.insertOne(new Document("_id", "payment-3")
                                                .append("bookingId", "booking-2")
                                                .append("customerId", "customer-1")
                                                .append("createdAt", Instant.parse(
                                                                "2026-11-16T12:00:00Z")));

                                payments.insertOne(new Document("_id", "payment-4")
                                                .append("bookingId", "booking-3")
                                                .append("customerId", "customer-2")
                                                .append("createdAt", Instant.parse(
                                                                "2026-11-16T13:00:00Z")));

                                // Duplicate gateway reference must be rejected.
                                MongoWriteException duplicate = assertThrows(
                                                MongoWriteException.class,
                                                () -> payments.insertOne(
                                                                new Document("_id", "payment-5")
                                                                                .append("bookingId", "booking-4")
                                                                                .append("customerId", "customer-3")
                                                                                .append("gatewayRef", "gateway-1")));

                                assertEquals(11000, duplicate.getCode());

                                // Duplicate PaymentIntent references must be rejected
                                // even when the gateway reference is different.
                                MongoWriteException duplicatePaymentIntent = assertThrows(
                                                MongoWriteException.class,
                                                () -> payments.insertOne(
                                                                new Document("_id", "payment-6")
                                                                                .append("bookingId", "booking-5")
                                                                                .append("customerId", "customer-3")
                                                                                .append("gatewayRef", "gateway-6")
                                                                                .append("gatewayPaymentIntentId", "pi_test001")));

                                assertEquals(11000, duplicatePaymentIntent.getCode());

                                // Multiple payments without a PaymentIntent remain valid.
                                assertEquals(
                                                2L,
                                                payments.countDocuments(
                                                                new Document(
                                                                                "gatewayPaymentIntentId",
                                                                                new Document("$exists", false))));

                                // Multiple payments for one customer remain valid.
                                assertEquals(
                                                3,
                                                payments.countDocuments(
                                                                new Document("customerId", "customer-1")));

                                assertEquals(4, payments.countDocuments());

                                // Repeated initialization must not create extra indexes.
                                assertEquals(
                                                5,
                                                payments.listIndexes()
                                                                .into(new ArrayList<>())
                                                                .size());

                        } finally {
                                // Never drop any database other than this test database.
                                client.getDatabase(databaseName).drop();
                        }
                }
        }

        /**
         * Verifies that an incompatible gateway index causes
         * initialization to fail without deleting the index.
         */
        @Test
        void rejectsIncompatibleIndexWithoutDeletingIt() {
                String databaseName = "wp07_index_conflict_test_"
                                + UUID.randomUUID().toString().replace("-", "");

                try (MongoClient client = MongoClients.create(LOCAL_URI)) {

                        MongoTemplate template = new MongoTemplate(
                                        new SimpleMongoClientDatabaseFactory(
                                                        client, databaseName));

                        MongoCollection<Document> payments = client.getDatabase(databaseName)
                                        .getCollection("payments");

                        try {
                                payments.createIndex(
                                                Indexes.ascending("gatewayRef"),
                                                new IndexOptions()
                                                                .name("gatewayRef_1")
                                                                .unique(false));

                                PaymentIndexInitializer initializer = new PaymentIndexInitializer(template);

                                IllegalStateException error = assertThrows(
                                                IllegalStateException.class,
                                                initializer::initializeIndexes);

                                assertTrue(error.getMessage().contains(
                                                "Incompatible payment index"));

                                List<Document> indexes = payments.listIndexes().into(new ArrayList<>());

                                Document incompatible = findIndex(indexes, "gatewayRef_1");

                                assertNotNull(incompatible);
                                assertNotEquals(
                                                Boolean.TRUE,
                                                incompatible.get("unique"));

                        } finally {
                                client.getDatabase(databaseName).drop();
                        }
                }
        }

        /**
         * Verifies that an incompatible customer history index
         * is rejected without deleting or replacing it.
         */
        @Test
        void rejectsIncompatibleCustomerHistoryIndexWithoutDeletingIt() {
                String databaseName = "wp07_customer_index_conflict_"
                                + UUID.randomUUID().toString().replace("-", "");

                try (MongoClient client = MongoClients.create(LOCAL_URI)) {

                        MongoTemplate template = new MongoTemplate(
                                        new SimpleMongoClientDatabaseFactory(
                                                        client, databaseName));

                        MongoCollection<Document> payments = client.getDatabase(databaseName)
                                        .getCollection("payments");

                        try {
                                // Deliberately use ascending createdAt order.
                                payments.createIndex(
                                                Indexes.compoundIndex(
                                                                Indexes.ascending("customerId"),
                                                                Indexes.ascending("createdAt"),
                                                                Indexes.descending("_id")),
                                                new IndexOptions()
                                                                .name(CUSTOMER_HISTORY_INDEX));

                                PaymentIndexInitializer initializer = new PaymentIndexInitializer(template);

                                IllegalStateException error = assertThrows(
                                                IllegalStateException.class,
                                                initializer::initializeIndexes);

                                assertTrue(error.getMessage().contains(
                                                "Incompatible payment index"));

                                List<Document> indexes = payments.listIndexes().into(new ArrayList<>());

                                Document incompatible = findIndex(indexes, CUSTOMER_HISTORY_INDEX);

                                // The incompatible index must remain unchanged.
                                assertNotNull(incompatible);

                                assertEquals(
                                                new Document("customerId", 1)
                                                                .append("createdAt", 1)
                                                                .append("_id", -1),
                                                incompatible.get("key", Document.class));

                        } finally {
                                client.getDatabase(databaseName).drop();
                        }
                }
        }

        /**
         * Finds MongoDB index metadata by index name.
         *
         * @param indexes database index metadata
         * @param name    expected index name
         * @return matching index metadata, or null
         */
        private Document findIndex(
                        List<Document> indexes,
                        String name) {

                return indexes.stream()
                                .filter(index -> name.equals(
                                                index.getString("name")))
                                .findFirst()
                                .orElse(null);
        }
}
