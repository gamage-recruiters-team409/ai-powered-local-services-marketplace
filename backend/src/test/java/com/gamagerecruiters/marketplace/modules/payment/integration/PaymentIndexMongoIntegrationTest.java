
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;

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
 * Never connects to Atlas or the application's configured database.
 */
class PaymentIndexMongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27017";

    @Test
    void createsAndEnforcesPaymentIndexes() {
        // Unique database per run prevents interference with other tests.
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

                // Fresh initialization and repeat initialization.
                initializer.initializeIndexes();
                initializer.initializeIndexes();

                List<Document> indexes = payments.listIndexes().into(new ArrayList<>());

                Document bookingIndex = findIndex(
                        indexes, "bookingId_1");

                Document gatewayIndex = findIndex(
                        indexes, "gatewayRef_1");

                assertNotNull(bookingIndex);
                assertNotNull(gatewayIndex);

                assertEquals(
                        new Document("bookingId", 1),
                        bookingIndex.get("key", Document.class));

                assertNotEquals(
                        Boolean.TRUE, bookingIndex.get("unique"));

                assertEquals(
                        new Document("gatewayRef", 1),
                        gatewayIndex.get("key", Document.class));

                assertEquals(
                        Boolean.TRUE, gatewayIndex.get("unique"));

                Document partialFilter = gatewayIndex.get(
                        "partialFilterExpression",
                        Document.class);

                assertNotNull(partialFilter);
                assertTrue(partialFilter.containsKey("gatewayRef"));

                // Same bookingId must allow multiple payment attempts.
                payments.insertOne(new Document("_id", "payment-1")
                        .append("bookingId", "booking-1")
                        .append("gatewayRef", "gateway-1"));

                payments.insertOne(new Document("_id", "payment-2")
                        .append("bookingId", "booking-1")
                        .append("gatewayRef", "gateway-2"));

                // Missing gatewayRef must be allowed more than once.
                payments.insertOne(new Document("_id", "payment-3")
                        .append("bookingId", "booking-2"));

                payments.insertOne(new Document("_id", "payment-4")
                        .append("bookingId", "booking-3"));

                // Duplicate gatewayRef must be rejected by MongoDB.
                MongoWriteException duplicate = assertThrows(
                        MongoWriteException.class,
                        () -> payments.insertOne(
                                new Document("_id", "payment-5")
                                        .append("bookingId", "booking-4")
                                        .append("gatewayRef", "gateway-1")));

                assertEquals(11000, duplicate.getCode());

                assertEquals(4, payments.countDocuments());

            } finally {
                // Only the uniquely named test database is removed.
                client.getDatabase(databaseName).drop();
            }
        }
    }

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
                // Deliberately create an incompatible non-unique index.
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

                Document incompatible = findIndex(
                        indexes, "gatewayRef_1");

                // Existing incompatible index must not be dropped.
                assertNotNull(incompatible);
                assertNotEquals(
                        Boolean.TRUE, incompatible.get("unique"));

            } finally {
                // Only the uniquely named test database is removed.
                client.getDatabase(databaseName).drop();
            }
        }
    }

    private Document findIndex(
            List<Document> indexes, String name) {

        return indexes.stream()
                .filter(index -> name.equals(
                        index.getString("name")))
                .findFirst()
                .orElse(null);
    }
}
