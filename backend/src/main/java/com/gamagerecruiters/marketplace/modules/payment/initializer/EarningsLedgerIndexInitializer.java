
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
 * The event-key index enforces event idempotency, while the provider
 * sequence index protects deterministic financial processing order.
 * </p>
 *
 * <p>
 * Existing incompatible indexes are never dropped automatically.
 * Manual migration is required to protect financial records.
 * </p>
 */
@Component
@Profile("!test")
public class EarningsLedgerIndexInitializer {

    static final String LEDGER_COLLECTION = "earnings_ledger";
    static final String EVENT_KEY_INDEX = "eventKey_1";

    /**
     * Unique processing sequence within each provider and currency.
     */
    static final String PROVIDER_SEQUENCE_INDEX = "providerId_1_currency_1_sequenceNumber_1";

    static final String PROVIDER_HISTORY_INDEX = "providerId_1_currency_1_sequenceNumber_-1__id_-1";

    static final String PROVIDER_ITEMIZED_INDEX = "providerId_1_currency_1_eventType_1_sequenceNumber_-1__id_-1";

    private static final Logger log = LoggerFactory.getLogger(EarningsLedgerIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public EarningsLedgerIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Verifies required financial indexes during application startup.
     *
     * @throws IllegalStateException if a required index cannot be
     *                               created or verified
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing MongoDB indexes for earnings ledger...");

        ensureEventKeyIndex();
        ensureProviderSequenceIndex();
        ensureProviderHistoryIndex();
        ensureProviderItemizedIndex();

        log.info("Earnings ledger indexes verified successfully.");
    }

    /**
     * Creates or verifies the unique event-key index.
     */
    void ensureEventKeyIndex() {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection = mongoTemplate.getCollection(LEDGER_COLLECTION);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, EVENT_KEY_INDEX);

            if (existing == null) {

                // Do not silently replace a differently configured index.
                Document conflicting = findByField(indexes, "eventKey");

                if (conflicting != null) {
                    throw incompatible(EVENT_KEY_INDEX, conflicting);
                }

                collection.createIndex(
                        Indexes.ascending("eventKey"),
                        new IndexOptions()
                                .name(EVENT_KEY_INDEX)
                                .unique(true));

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByName(indexes, EVENT_KEY_INDEX);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Earnings ledger event-key index was not found "
                                    + "after creation.");
                }
            }

            if (!hasExpectedEventKey(existing)
                    || !hasExpectedUniqueness(existing)
                    || existing.containsKey("partialFilterExpression")
                    || existing.containsKey("sparse")) {
                throw incompatible(EVENT_KEY_INDEX, existing);
            }

            log.info(
                    "Earnings ledger index verified: {} -> {}",
                    EVENT_KEY_INDEX,
                    existing.toJson());

        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create or verify earnings ledger index '"
                            + EVENT_KEY_INDEX + "'.",
                    exception);
        }
    }

    /**
     * Creates or verifies the provider-scoped unique processing index.
     *
     * <p>
     * The partial filter excludes legacy ledger records that do not
     * contain a numeric sequenceNumber. Existing financial documents
     * are not modified or deleted.
     * </p>
     */
    void ensureProviderSequenceIndex() {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection = mongoTemplate.getCollection(LEDGER_COLLECTION);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, PROVIDER_SEQUENCE_INDEX);

            // Apply uniqueness only to sequenced financial events.
            Document expectedFilter = new Document(
                    "sequenceNumber",
                    new Document("$type", "number"));

            if (existing == null) {

                // Reject an existing index with the same key pattern
                // but an incompatible name or configuration.
                for (Document index : indexes) {
                    if (hasExpectedProviderSequenceKey(index)) {
                        throw incompatible(PROVIDER_SEQUENCE_INDEX, index);
                    }
                }

                collection.createIndex(
                        Indexes.ascending(
                                "providerId",
                                "currency",
                                "sequenceNumber"),
                        new IndexOptions()
                                .name(PROVIDER_SEQUENCE_INDEX)
                                .unique(true)
                                .partialFilterExpression(expectedFilter));

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByName(
                        indexes,
                        PROVIDER_SEQUENCE_INDEX);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Provider sequence index was not found "
                                    + "after creation.");
                }
            }

            Document actualFilter = existing.get("partialFilterExpression", Document.class);

            if (!hasExpectedProviderSequenceKey(existing)
                    || !hasExpectedUniqueness(existing)
                    || !expectedFilter.equals(actualFilter)
                    || existing.containsKey("sparse")) {
                throw incompatible(PROVIDER_SEQUENCE_INDEX, existing);
            }

            log.info(
                    "Earnings ledger index verified: {} -> {}",
                    PROVIDER_SEQUENCE_INDEX,
                    existing.toJson());

        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create or verify earnings ledger index '"
                            + PROVIDER_SEQUENCE_INDEX + "'.",
                    exception);
        }
    }

    /**
     * Creates the ledger collection if it does not already exist.
     *
     * Concurrent collection creation is tolerated without deleting
     * or modifying existing financial data.
     */
    private void ensureCollectionExists() {
        if (mongoTemplate.collectionExists(LEDGER_COLLECTION)) {
            return;
        }

        try {
            mongoTemplate.createCollection(LEDGER_COLLECTION);

            log.info("Collection created: {}", LEDGER_COLLECTION);

        } catch (RuntimeException exception) {
            if (!mongoTemplate.collectionExists(LEDGER_COLLECTION)) {
                throw exception;
            }

            log.info(
                    "Earnings ledger collection was created concurrently.");
        }
    }

    /**
     * Finds an existing index by its MongoDB index name.
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
     * Finds an index containing a specified field.
     */
    private Document findByField(
            List<Document> indexes,
            String field) {

        for (Document index : indexes) {
            Document key = index.get("key", Document.class);

            if (key != null && key.containsKey(field)) {
                return index;
            }
        }

        return null;
    }

    /**
     * Verifies the expected single-field event-key index.
     */
    private boolean hasExpectedEventKey(Document index) {
        Document key = index.get("key", Document.class);

        return key != null
                && key.size() == 1
                && isAscending(key, "eventKey");
    }

    /**
     * Verifies the exact provider/currency/sequence index key order.
     */
    private boolean hasExpectedProviderSequenceKey(Document index) {
        Document key = index.get("key", Document.class);

        if (key == null || key.size() != 3) {
            return false;
        }

        List<String> fields = new ArrayList<>(key.keySet());

        return fields.equals(List.of(
                "providerId",
                "currency",
                "sequenceNumber"))
                && isAscending(key, "providerId")
                && isAscending(key, "currency")
                && isAscending(key, "sequenceNumber");
    }

    /**
     * Checks whether an indexed field uses ascending direction.
     */
    private boolean isAscending(Document key, String field) {
        Object direction = key.get(field);

        return direction instanceof Number number
                && number.intValue() == 1;
    }

    /**
     * Verifies that MongoDB enforces uniqueness.
     */
    private boolean hasExpectedUniqueness(Document index) {
        Object value = index.get("unique");

        return Boolean.TRUE.equals(value)
                || (value instanceof Number number
                        && number.intValue() == 1);
    }

    /**
     * Reports incompatible indexes without automatically dropping them.
     */
    private IllegalStateException incompatible(
            String expectedIndexName,
            Document found) {

        return new IllegalStateException(
                "Incompatible earnings ledger index '"
                        + expectedIndexName
                        + "' detected: "
                        + found.toJson()
                        + ". Automatic index deletion is disabled "
                        + "to protect financial records. "
                        + "Manual database migration is required.");
    }

    /**
     * Creates or verifies the index supporting paginated provider
     * earnings history, including legacy unsequenced entries.
     */
    void ensureProviderHistoryIndex() {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection =
                    mongoTemplate.getCollection(LEDGER_COLLECTION);

            Document expectedKey = new Document("providerId", 1)
                    .append("currency", 1)
                    .append("sequenceNumber", -1)
                    .append("_id", -1);

            List<Document> indexes =
                    collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, PROVIDER_HISTORY_INDEX);

            if (existing == null) {
                for (Document index : indexes) {
                    if (hasExpectedHistoryKey(index, expectedKey)) {
                        throw incompatible(PROVIDER_HISTORY_INDEX, index);
                    }
                }

                collection.createIndex(
                        expectedKey,
                        new IndexOptions()
                                .name(PROVIDER_HISTORY_INDEX)
                                .unique(false));

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByName(indexes, PROVIDER_HISTORY_INDEX);
            }

            if (existing == null) {
                throw new IllegalStateException(
                        "Provider history index was not found after creation.");
            }

            if (!hasExpectedHistoryKey(existing, expectedKey)
                    || hasExpectedUniqueness(existing)
                    || existing.containsKey("partialFilterExpression")
                    || Boolean.TRUE.equals(existing.get("sparse"))
                    || Boolean.TRUE.equals(existing.get("hidden"))
                    || existing.containsKey("expireAfterSeconds")
                    || existing.containsKey("collation")) {
                throw incompatible(PROVIDER_HISTORY_INDEX, existing);
            }

            log.info(
                    "Earnings ledger index verified: {}",
                    PROVIDER_HISTORY_INDEX);

        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create or verify earnings ledger index '"
                            + PROVIDER_HISTORY_INDEX + "'.",
                    exception);
        }
    }

    /**
     * Verifies the exact field order and direction of the history index.
     */
    private boolean hasExpectedHistoryKey(
            Document index,
            Document expectedKey) {

        Document actualKey = index.get("key", Document.class);

        return actualKey != null
                && new ArrayList<>(actualKey.keySet())
                        .equals(new ArrayList<>(expectedKey.keySet()))
                && expectedKey.equals(actualKey);
    }

    /**
     * Creates or verifies the compound index supporting provider-scoped
     * recognition event queries with deterministic pagination.
     */
    void ensureProviderItemizedIndex() {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection =
                    mongoTemplate.getCollection(LEDGER_COLLECTION);

            Document expectedKey = new Document("providerId", 1)
                    .append("currency", 1)
                    .append("eventType", 1)
                    .append("sequenceNumber", -1)
                    .append("_id", -1);

            List<Document> indexes =
                    collection.listIndexes().into(new ArrayList<>());

            Document existing =
                    findByName(indexes, PROVIDER_ITEMIZED_INDEX);

            if (existing == null) {
                for (Document index : indexes) {
                    if (hasExpectedHistoryKey(index, expectedKey)) {
                        throw incompatible(PROVIDER_ITEMIZED_INDEX, index);
                    }
                }

                collection.createIndex(
                        expectedKey,
                        new IndexOptions()
                                .name(PROVIDER_ITEMIZED_INDEX)
                                .unique(false));

                indexes = collection.listIndexes().into(new ArrayList<>());

                existing = findByName(indexes, PROVIDER_ITEMIZED_INDEX);
            }

            if (existing == null) {
                throw new IllegalStateException(
                        "Provider itemized earnings index was not found after creation.");
            }

            if (!hasExpectedHistoryKey(existing, expectedKey)
                    || hasExpectedUniqueness(existing)
                    || existing.containsKey("partialFilterExpression")
                    || Boolean.TRUE.equals(existing.get("sparse"))
                    || Boolean.TRUE.equals(existing.get("hidden"))
                    || existing.containsKey("expireAfterSeconds")
                    || existing.containsKey("collation")) {

                throw incompatible(PROVIDER_ITEMIZED_INDEX, existing);
            }

            log.info(
                    "Earnings ledger index verified: {}",
                    PROVIDER_ITEMIZED_INDEX);

        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create or verify earnings ledger index '"
                            + PROVIDER_ITEMIZED_INDEX + "'.",
                    exception);
        }
    }

}
