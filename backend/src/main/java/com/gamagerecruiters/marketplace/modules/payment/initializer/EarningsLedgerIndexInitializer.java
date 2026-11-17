
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
import com.mongodb.client.model.Indexes;

/**
 * Initializes and verifies MongoDB indexes for provider earnings ledger
 * entries.
 *
 * <p>
 * Each financial event must have a unique event key to prevent
 * duplicate ledger entries, including concurrent duplicate inserts.
 * </p>
 *
 * <p>
 * Existing incompatible indexes are never dropped automatically.
 * Manual database migration is required to protect financial records.
 * </p>
 *
 * <p>
 * Database uniqueness does not enforce append-only behavior.
 * Controlled persistence services must prevent updates and deletions.
 * </p>
 */
@Component
@Profile("!test")
public class EarningsLedgerIndexInitializer {

    static final String LEDGER_COLLECTION = "earnings_ledger";
    static final String EVENT_KEY_INDEX = "eventKey_1";

    private static final Logger log = LoggerFactory.getLogger(EarningsLedgerIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public EarningsLedgerIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Verifies the unique financial event index when the application is ready.
     *
     * @throws IllegalStateException if index creation or verification fails
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing MongoDB indexes for earnings ledger...");

        ensureEventKeyIndex();

        log.info("Earnings ledger indexes verified successfully.");
    }

    /**
     * Creates the unique event-key index if it is missing, or validates
     * the existing index without modifying financial records.
     *
     * @throws IllegalStateException if an incompatible index is found,
     *                               duplicate event keys exist, or MongoDB
     *                               operations fail
     */
    void ensureEventKeyIndex() {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection = mongoTemplate.getCollection(LEDGER_COLLECTION);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, EVENT_KEY_INDEX);

            if (existing == null) {

                // A differently named index on the same field may conflict
                // with the expected unique-index configuration.
                Document conflicting = findByField(indexes, "eventKey");

                if (conflicting != null) {
                    throw incompatible(conflicting);
                }

                // Enforce uniqueness for every ledger event key.
                IndexOptions options = new IndexOptions()
                        .name(EVENT_KEY_INDEX)
                        .unique(true);

                collection.createIndex(
                        Indexes.ascending("eventKey"),
                        options);

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByName(indexes, EVENT_KEY_INDEX);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Earnings ledger event-key index was not found "
                                    + "after creation.");
                }
            }

            // Reject incorrect index definitions instead of silently
            // modifying or deleting existing financial database indexes.
            if (!hasExpectedKey(existing)
                    || !hasExpectedUniqueness(existing)
                    || existing.containsKey("partialFilterExpression")
                    || existing.containsKey("sparse")) {
                throw incompatible(existing);
            }

            log.info(
                    "Earnings ledger index verified: {} -> {}",
                    EVENT_KEY_INDEX,
                    existing.toJson());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create or verify earnings ledger index '"
                            + EVENT_KEY_INDEX
                            + "'. Check MongoDB connectivity, existing indexes, "
                            + "and duplicate event keys.",
                    e);
        }
    }

    /**
     * Creates the earnings ledger collection when it does not exist.
     *
     * Handles concurrent collection creation without deleting data.
     */
    private void ensureCollectionExists() {
        if (mongoTemplate.collectionExists(LEDGER_COLLECTION)) {
            return;
        }

        try {
            mongoTemplate.createCollection(LEDGER_COLLECTION);
            log.info("Collection created: {}", LEDGER_COLLECTION);
        } catch (RuntimeException e) {
            if (!mongoTemplate.collectionExists(LEDGER_COLLECTION)) {
                throw e;
            }

            log.info("Earnings ledger collection was created concurrently.");
        }
    }

    /**
     * Finds an index using its configured MongoDB index name.
     */
    private Document findByName(List<Document> indexes, String indexName) {
        for (Document index : indexes) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }

        return null;
    }

    /**
     * Finds an index whose key definition matches the eventKey field.
     */
    private Document findByField(List<Document> indexes, String field) {
        for (Document index : indexes) {
            Document key = index.get("key", Document.class);

            if (key != null && key.containsKey(field)) {
                return index;
            }
        }

        return null;
    }

    /**
     * Verifies that the index contains only eventKey in ascending order.
     */
    private boolean hasExpectedKey(Document index) {
        Document key = index.get("key", Document.class);

        if (key == null || key.size() != 1) {
            return false;
        }

        Object direction = key.get("eventKey");

        return direction instanceof Number number
                && number.intValue() == 1;
    }

    /**
     * Verifies the database-level uniqueness constraint.
     */
    private boolean hasExpectedUniqueness(Document index) {
        Object value = index.get("unique");

        return Boolean.TRUE.equals(value)
                || (value instanceof Number number
                        && number.intValue() == 1);
    }

    /**
     * Builds a descriptive error without dropping incompatible indexes.
     */
    private IllegalStateException incompatible(Document found) {
        return new IllegalStateException(
                "Incompatible earnings ledger index '"
                        + EVENT_KEY_INDEX
                        + "' detected: " + found.toJson()
                        + ". Automatic index deletion is disabled "
                        + "to protect financial records. "
                        + "Manual database migration is required.");
    }
}
