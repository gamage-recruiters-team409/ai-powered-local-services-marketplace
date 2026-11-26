
package com.gamagerecruiters.marketplace.modules.payment.initializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

/**
 * Initializes and validates MongoDB indexes for refund operations.
 *
 * <p>
 * Unique indexes protect refund idempotency and prevent duplicate
 * gateway refund references. Non-unique indexes support payment-level
 * tracking, dispute integration and reconciliation.
 * </p>
 *
 * <p>
 * Existing incompatible indexes are never automatically deleted.
 * Financial data migrations must be performed explicitly.
 * </p>
 */
@Component
@Profile("!test")
public class RefundOperationIndexInitializer {

    static final String REFUNDS_COLLECTION = "payment_refund_operations";

    static final String IDEMPOTENCY_KEY_INDEX = "idempotencyKey_1";

    static final String GATEWAY_REFUND_REFERENCE_INDEX = "gatewayRefundReference_1";

    static final String PAYMENT_ID_INDEX = "paymentId_1";

    static final String PAYMENT_HISTORY_INDEX =
            "paymentId_1_createdAt_-1__id_-1";

    static final String DISPUTE_ID_INDEX = "disputeId_1";

    static final String RECONCILIATION_INDEX = "status_1_updatedAt_1__id_1";

    private static final Logger log = LoggerFactory.getLogger(
            RefundOperationIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public RefundOperationIndexInitializer(
            MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Creates or validates all required refund operation indexes
     * when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {

        log.info("Initializing MongoDB indexes for refund operations...");

        ensureCollectionExists();

        // Enforce one persisted operation per business idempotency key.
        ensureIndex(
                IDEMPOTENCY_KEY_INDEX,
                new Document("idempotencyKey", 1),
                true,
                null);

        // Allow missing gateway references while rejecting duplicates.
        ensureIndex(
                GATEWAY_REFUND_REFERENCE_INDEX,
                new Document("gatewayRefundReference", 1),
                true,
                new Document(
                        "gatewayRefundReference",
                        new Document("$type", "string")));

        // A payment may have multiple partial refund operations.
        ensureIndex(
                PAYMENT_ID_INDEX,
                new Document("paymentId", 1),
                false,
                null);

        // Support deterministic, paginated customer refund history.
        ensureIndex(
                PAYMENT_HISTORY_INDEX,
                new Document("paymentId", 1)
                        .append("createdAt", -1)
                        .append("_id", -1),
                false,
                null);

        // Support dispute-related refund lookup.
        ensureIndex(
                DISPUTE_ID_INDEX,
                new Document("disputeId", 1),
                false,
                null);

        // Support bounded, deterministically ordered recovery queries.
        ensureIndex(
                RECONCILIATION_INDEX,
                new Document("status", 1)
                        .append("updatedAt", 1)
                        .append("_id", 1),
                false,
                null);

        log.info("Refund operation indexes verified successfully.");
    }

    /**
     * Creates a missing index or validates an existing index.
     *
     * @param indexName     expected index name
     * @param expectedKey   expected ordered index fields
     * @param unique        required uniqueness constraint
     * @param partialFilter optional partial index filter
     */
    void ensureIndex(
            String indexName,
            Document expectedKey,
            boolean unique,
            Document partialFilter) {

        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(REFUNDS_COLLECTION);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, indexName);

            if (existing == null) {

                // Reject conflicting indexes rather than dropping them.
                Document conflicting = findByExactKey(indexes, expectedKey);

                if (conflicting != null) {
                    throw incompatible(indexName, conflicting);
                }

                IndexOptions options = new IndexOptions()
                        .name(indexName)
                        .unique(unique);

                if (partialFilter != null) {
                    options.partialFilterExpression(partialFilter);
                }

                collection.createIndex(expectedKey, options);

                // Re-read persisted metadata to verify index creation.
                indexes = collection.listIndexes()
                        .into(new ArrayList<>());

                existing = findByName(indexes, indexName);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Refund operation index '" + indexName
                                    + "' was not found after creation.");
                }
            }

            if (!hasExpectedKey(existing, expectedKey)
                    || !hasExpectedUniqueness(existing, unique)
                    || !hasExpectedPartialFilter(
                            existing, partialFilter)
                    || existing.get("sparse") != null
                    || existing.get("expireAfterSeconds") != null
                    || Boolean.TRUE.equals(existing.get("hidden"))
                    || !hasSimpleCollation(existing)) {

                throw incompatible(indexName, existing);
            }

            log.info(
                    "Refund operation index verified: {} -> {}",
                    indexName,
                    existing.toJson());

        } catch (IllegalStateException e) {
            throw e;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create or verify refund operation index '"
                            + indexName
                            + "'. Check MongoDB connectivity, existing "
                            + "indexes and duplicate refund references.",
                    e);
        }
    }

    /**
     * Ensures the refund collection exists before index creation.
     *
     * <p>
     * Handles concurrent collection creation by another
     * application instance.
     * </p>
     */
    private void ensureCollectionExists() {

        if (mongoTemplate.collectionExists(REFUNDS_COLLECTION)) {
            return;
        }

        try {
            mongoTemplate.createCollection(REFUNDS_COLLECTION);

            log.info("Collection created: {}", REFUNDS_COLLECTION);

        } catch (RuntimeException e) {

            if (!mongoTemplate.collectionExists(REFUNDS_COLLECTION)) {
                throw e;
            }

            log.info("Refund operation collection was created concurrently.");
        }
    }

    /**
     * Finds an index using its configured name.
     */
    private Document findByName(
            List<Document> indexes,
            String indexName) {

        for (Document index : indexes) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }

        return null;
    }

    /**
     * Finds an index with the exact expected ordered key.
     */
    private Document findByExactKey(
            List<Document> indexes,
            Document expectedKey) {

        for (Document index : indexes) {
            if (hasExpectedKey(index, expectedKey)) {
                return index;
            }
        }

        return null;
    }

    /**
     * Validates index field names, field order and sort directions.
     */
    private boolean hasExpectedKey(
            Document index,
            Document expectedKey) {

        Document actualKey = index.get("key", Document.class);

        if (actualKey == null
                || actualKey.size() != expectedKey.size()) {
            return false;
        }

        var actualEntries = actualKey.entrySet().iterator();
        var expectedEntries = expectedKey.entrySet().iterator();

        while (actualEntries.hasNext()
                && expectedEntries.hasNext()) {

            var actual = actualEntries.next();
            var expected = expectedEntries.next();

            if (!actual.getKey().equals(expected.getKey())) {
                return false;
            }

            if (!(actual.getValue() instanceof Number actualDirection)
                    || !(expected.getValue() instanceof Number expectedDirection)
                    || actualDirection.doubleValue() != expectedDirection.doubleValue()) {

                return false;
            }
        }

        return !actualEntries.hasNext()
                && !expectedEntries.hasNext();
    }

    /**
     * Validates the uniqueness constraint.
     *
     * <p>
     * MongoDB may omit the unique property for non-unique
     * indexes.
     * </p>
     */
    private boolean hasExpectedUniqueness(
            Document index,
            boolean expectedUnique) {

        Object value = index.get("unique");

        if (value == null) {
            return !expectedUnique;
        }

        if (value instanceof Boolean booleanValue) {
            return booleanValue == expectedUnique;
        }

        if (value instanceof Number number) {
            return number.doubleValue() == (expectedUnique ? 1.0 : 0.0);
        }

        return false;
    }

    /**
     * Validates the complete partial filter expression.
     */
    private boolean hasExpectedPartialFilter(
            Document index,
            Document expectedFilter) {

        Document actualFilter = index.get("partialFilterExpression", Document.class);

        return Objects.equals(actualFilter, expectedFilter);
    }

    /**
     * Rejects non-simple collations to preserve exact,
     * case-sensitive financial identifier comparisons.
     */
    private boolean hasSimpleCollation(Document index) {

        Document collation = index.get("collation", Document.class);

        return collation == null
                || "simple".equals(collation.getString("locale"));
    }

    /**
     * Reports an incompatible index without modifying
     * existing database indexes or financial records.
     */
    private IllegalStateException incompatible(
            String indexName,
            Document found) {

        return new IllegalStateException(
                "Incompatible refund operation index '"
                        + indexName
                        + "' detected: "
                        + found.toJson()
                        + ". Automatic index deletion is disabled "
                        + "to protect financial records. "
                        + "Manual database migration is required.");
    }
}
