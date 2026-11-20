
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.gamagerecruiters.marketplace.modules.payment.initializer.PayoutIndexInitializer;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Verifies WP07 payout uniqueness constraints against a local MongoDB instance.
 *
 * <p>
 * Every test uses an isolated temporary database on localhost.
 * Only that temporary database is dropped during cleanup.
 * </p>
 */
class PayoutIndexMongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27017";

    /**
     * Verifies index creation, idempotent initialization, and real
     * MongoDB duplicate-key rejection for all three financial collections.
     */
    @Test
    void createsAndEnforcesPayoutUniqueIndexes() {
        String databaseName = temporaryDatabaseName("wp07_payout_indexes_");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {
            MongoTemplate template = template(client, databaseName);

            MongoCollection<Document> payouts = collection(
                    client, databaseName, "provider_payouts");
            MongoCollection<Document> holds = collection(
                    client, databaseName, "dispute_payout_holds");
            MongoCollection<Document> allocations = collection(
                    client, databaseName, "payout_allocations");

            try {
                PayoutIndexInitializer initializer = new PayoutIndexInitializer(template);

                // Running initialization twice must be safe.
                initializer.initializeIndexes();
                initializer.initializeIndexes();

                assertUniqueIndex(
                        payouts,
                        "requestKey_1",
                        new Document("requestKey", 1));

                assertUniqueIndex(
                        holds,
                        "disputeId_1",
                        new Document("disputeId", 1));

                assertUniqueIndex(
                        allocations,
                        "uq_payout_payment_booking",
                        new Document("payoutId", 1)
                                .append("paymentId", 1)
                                .append("bookingId", 1));

                // Request-key uniqueness protects payout idempotency.
                payouts.insertOne(new Document("_id", "payout-1")
                        .append("requestKey", "request-1"));

                MongoWriteException duplicateRequest = assertThrows(
                        MongoWriteException.class,
                        () -> payouts.insertOne(
                                new Document("_id", "payout-2")
                                        .append("requestKey", "request-1")));

                assertEquals(11000, duplicateRequest.getCode());

                // A different request key must remain valid.
                payouts.insertOne(new Document("_id", "payout-3")
                        .append("requestKey", "request-2"));

                // A dispute must not create duplicate financial holds.
                holds.insertOne(new Document("_id", "hold-1")
                        .append("disputeId", "dispute-1"));

                MongoWriteException duplicateDispute = assertThrows(
                        MongoWriteException.class,
                        () -> holds.insertOne(
                                new Document("_id", "hold-2")
                                        .append("disputeId", "dispute-1")));

                assertEquals(11000, duplicateDispute.getCode());

                // The complete allocation tuple must be unique.
                allocations.insertOne(allocation(
                        "allocation-1", "payout-1", "payment-1", "booking-1"));

                MongoWriteException duplicateAllocation = assertThrows(
                        MongoWriteException.class,
                        () -> allocations.insertOne(allocation(
                                "allocation-2",
                                "payout-1",
                                "payment-1",
                                "booking-1")));

                assertEquals(11000, duplicateAllocation.getCode());

                // Different payment or booking combinations remain valid.
                allocations.insertOne(allocation(
                        "allocation-3", "payout-1", "payment-2", "booking-1"));

                allocations.insertOne(allocation(
                        "allocation-4", "payout-1", "payment-1", "booking-2"));

                assertEquals(2, payouts.countDocuments());
                assertEquals(1, holds.countDocuments());
                assertEquals(3, allocations.countDocuments());

                // Each collection has _id plus its required unique index.
                assertEquals(2, indexCount(payouts));
                assertEquals(2, indexCount(holds));
                assertEquals(2, indexCount(allocations));

            } finally {
                client.getDatabase(databaseName).drop();
            }
        }
    }

    /**
     * An incompatible non-unique request-key index must not be replaced.
     */
    @Test
    void rejectsIncompatiblePayoutIndexWithoutDeletingIt() {
        String databaseName = temporaryDatabaseName("wp07_payout_conflict_");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {
            MongoTemplate template = template(client, databaseName);
            MongoCollection<Document> payouts = collection(
                    client, databaseName, "provider_payouts");

            try {
                payouts.createIndex(
                        Indexes.ascending("requestKey"),
                        new IndexOptions()
                                .name("requestKey_1")
                                .unique(false));

                PayoutIndexInitializer initializer = new PayoutIndexInitializer(template);

                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        initializer::initializeIndexes);

                assertTrue(error.getMessage().contains(
                        "Incompatible WP07 financial index"));

                Document existing = findIndex(payouts, "requestKey_1");

                assertNotNull(existing);
                assertNotEquals(Boolean.TRUE, existing.get("unique"));
                assertEquals(2, indexCount(payouts));

            } finally {
                client.getDatabase(databaseName).drop();
            }
        }
    }

    /**
     * A conflicting allocation index must not be silently replaced.
     */
    @Test
    void rejectsIncompatibleAllocationIndexWithoutDeletingIt() {
        String databaseName = temporaryDatabaseName("wp07_allocation_conflict_");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {
            MongoTemplate template = template(client, databaseName);
            MongoCollection<Document> allocations = collection(
                    client, databaseName, "payout_allocations");

            try {
                allocations.createIndex(
                        Indexes.ascending(
                                "payoutId", "paymentId", "bookingId"),
                        new IndexOptions()
                                .name("uq_payout_payment_booking")
                                .unique(false));

                PayoutIndexInitializer initializer = new PayoutIndexInitializer(template);

                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        initializer::initializeIndexes);

                assertTrue(error.getMessage().contains(
                        "Incompatible WP07 financial index"));

                Document existing = findIndex(
                        allocations, "uq_payout_payment_booking");

                assertNotNull(existing);
                assertNotEquals(Boolean.TRUE, existing.get("unique"));
                assertEquals(2, indexCount(allocations));

            } finally {
                client.getDatabase(databaseName).drop();
            }
        }
    }

    private String temporaryDatabaseName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private MongoTemplate template(MongoClient client, String databaseName) {
        return new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(client, databaseName));
    }

    private MongoCollection<Document> collection(
            MongoClient client,
            String databaseName,
            String collectionName) {

        return client.getDatabase(databaseName).getCollection(collectionName);
    }

    private Document allocation(
            String id,
            String payoutId,
            String paymentId,
            String bookingId) {

        return new Document("_id", id)
                .append("payoutId", payoutId)
                .append("paymentId", paymentId)
                .append("bookingId", bookingId);
    }

    private void assertUniqueIndex(
            MongoCollection<Document> collection,
            String indexName,
            Document expectedKey) {

        Document index = findIndex(collection, indexName);

        assertNotNull(index, "Missing unique index: " + indexName);
        assertEquals(expectedKey, index.get("key", Document.class));
        assertEquals(Boolean.TRUE, index.get("unique"));
        assertFalse(index.containsKey("partialFilterExpression"));
        assertFalse(index.containsKey("sparse"));
    }

    private Document findIndex(
            MongoCollection<Document> collection,
            String indexName) {

        List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

        return indexes.stream()
                .filter(index -> indexName.equals(index.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private int indexCount(MongoCollection<Document> collection) {
        return collection.listIndexes().into(new ArrayList<>()).size();
    }
}
