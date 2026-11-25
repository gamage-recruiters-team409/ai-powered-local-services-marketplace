
package com.gamagerecruiters.marketplace.modules.payment.initializer;

import java.util.ArrayList;
import java.util.List;

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
 * Provisions and verifies unique MongoDB indexes for WP07 payout
 * reservations and dispute payout holds.
 *
 * <p>
 * Existing financial indexes are never dropped or replaced.
 * Incompatible definitions require an explicit database migration.
 * </p>
 */
@Component
@Profile("!test")
public class PayoutIndexInitializer {

    static final String PAYOUTS = "provider_payouts";
    static final String HOLDS = "dispute_payout_holds";
    static final String ALLOCATIONS = "payout_allocations";

    static final String REQUEST_KEY_INDEX = "requestKey_1";
    static final String DISPUTE_ID_INDEX = "disputeId_1";
    static final String ALLOCATION_INDEX = "uq_payout_payment_booking";

    private static final Logger log = LoggerFactory.getLogger(PayoutIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public PayoutIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Ensures required financial uniqueness constraints at startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        ensureUniqueIndex(
                PAYOUTS,
                REQUEST_KEY_INDEX,
                new Document("requestKey", 1));

        ensureUniqueIndex(
                HOLDS,
                DISPUTE_ID_INDEX,
                new Document("disputeId", 1));

        ensureUniqueIndex(
                ALLOCATIONS,
                ALLOCATION_INDEX,
                new Document("payoutId", 1)
                        .append("paymentId", 1)
                        .append("bookingId", 1));

        log.info("WP07 payout unique indexes verified successfully.");
    }

    /**
     * Creates a missing index or validates the complete existing definition.
     *
     * @param collectionName target MongoDB collection
     * @param indexName      expected index name
     * @param expectedKey    exact field order and direction
     */
    void ensureUniqueIndex(
            String collectionName,
            String indexName,
            Document expectedKey) {

        try {
            ensureCollectionExists(collectionName);

            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, indexName);

            if (existing == null) {
                // Never silently replace an index using the same key.
                for (Document index : indexes) {
                    if (expectedKey.equals(index.get("key", Document.class))) {
                        throw incompatible(collectionName, indexName, index);
                    }
                }

                collection.createIndex(
                        expectedKey,
                        new IndexOptions()
                                .name(indexName)
                                .unique(true));

                indexes = collection.listIndexes().into(new ArrayList<>());

                existing = findByName(indexes, indexName);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Financial index '" + indexName
                                    + "' was not found after creation.");
                }
            }

            if (!hasExpectedDefinition(existing, expectedKey)) {
                throw incompatible(collectionName, indexName, existing);
            }

            log.info(
                    "WP07 financial index verified: {}.{} -> {}",
                    collectionName,
                    indexName,
                    existing.toJson());

        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create or verify WP07 financial index '"
                            + collectionName + "." + indexName
                            + "'. Check MongoDB connectivity, existing "
                            + "indexes, and duplicate financial records.",
                    exception);
        }
    }

    /**
     * Verifies key order, uniqueness, and absence of unsupported filters.
     */
    private boolean hasExpectedDefinition(
            Document index,
            Document expectedKey) {

        Document actualKey = index.get("key", Document.class);

        return expectedKey.equals(actualKey)
                && Boolean.TRUE.equals(index.get("unique"))
                && !index.containsKey("partialFilterExpression")
                && !index.containsKey("sparse")
                && !index.containsKey("expireAfterSeconds");
    }

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
     * Creates missing collections without modifying existing records.
     */
    private void ensureCollectionExists(String collectionName) {
        if (mongoTemplate.collectionExists(collectionName)) {
            return;
        }

        try {
            mongoTemplate.createCollection(collectionName);
        } catch (RuntimeException exception) {
            if (!mongoTemplate.collectionExists(collectionName)) {
                throw exception;
            }
        }
    }

    private IllegalStateException incompatible(
            String collectionName,
            String indexName,
            Document existing) {

        return new IllegalStateException(
                "Incompatible WP07 financial index '"
                        + collectionName + "." + indexName
                        + "' detected: " + existing.toJson()
                        + ". Automatic index deletion is disabled "
                        + "to protect financial records. "
                        + "Manual database migration is required.");
    }
}
