package com.gamagerecruiters.marketplace.modules.booking.initializer;

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
 * Creates and verifies the indexes of the bookings collection (SRS 6.3):
 * unique {@code ref}, and compound {@code (providerId, slotStart)} and {@code (customerId, status)}.
 *
 * <p>Creates the collection first on a fresh database, reads the real index definitions from
 * MongoDB, checks key fields, order, direction, the unique flag, and that the index is neither
 * partial nor sparse, and fails startup on an incompatible index. Existing indexes are never dropped automatically.
 */
@Component
@Profile("!test")
public class BookingIndexInitializer {

    static final String BOOKINGS = "bookings";
    static final String REF_INDEX = "ref_1";
    static final String PROVIDER_SLOT_INDEX = "providerId_1_slotStart_1";
    static final String CUSTOMER_STATUS_INDEX = "customerId_1_status_1";

    private static final Logger log = LoggerFactory.getLogger(BookingIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public BookingIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for bookings collection...");

        ensureCollectionExists(BOOKINGS);
        ensureIndex(BOOKINGS, List.of("ref"), REF_INDEX, true);
        ensureIndex(BOOKINGS, List.of("providerId", "slotStart"), PROVIDER_SLOT_INDEX, false);
        ensureIndex(BOOKINGS, List.of("customerId", "status"), CUSTOMER_STATUS_INDEX, false);

        log.info("All required MongoDB indexes for booking module verified successfully.");
    }

    void ensureIndex(String collectionName, List<String> fields, String indexName, boolean unique) {
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByKey(indexes, fields);
            if (existing == null) {
                Document sameName = findByName(indexes, indexName);
                if (sameName != null) {
                    throw incompatible(collectionName, fields, unique, sameName);
                }

                collection.createIndex(Indexes.ascending(fields), new IndexOptions().unique(unique).name(indexName));

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByKey(indexes, fields);
                if (existing == null) {
                    throw new IllegalStateException("Startup failed: index '" + indexName + "' on "
                            + collectionName + " was not found after creation.");
                }
            }

            // A partial or sparse index skips documents without the field, so a unique one would
            // allow many bookings without a ref. Required indexes must cover every document.
            if (isUniqueTrue(existing) != unique
                    || existing.containsKey("partialFilterExpression")
                    || isSparseTrue(existing)) {
                throw incompatible(collectionName, fields, unique, existing);
            }

            log.info("Index verified: {} -> {}", collectionName, existing.toJson());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Startup failed: Unable to create or verify required index '"
                    + indexName + "' on " + collectionName + " collection."
                    + (unique ? " Ensure no duplicate values exist for " + fields + "." : ""), e);
        }
    }

    /** Creates the collection on a fresh database so listIndexes() never runs on a missing namespace. */
    void ensureCollectionExists(String collectionName) {
        try {
            if (mongoTemplate.collectionExists(collectionName)) {
                return;
            }
            try {
                mongoTemplate.createCollection(collectionName);
                log.info("Collection created: {}", collectionName);
            } catch (RuntimeException e) {
                if (!mongoTemplate.collectionExists(collectionName)) {
                    throw e;
                }
                log.info("Collection {} was created concurrently by another instance", collectionName);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Startup failed: Unable to create required collection '"
                    + collectionName + "'.", e);
        }
    }

    private Document findByKey(List<Document> indexes, List<String> fields) {
        for (Document index : indexes) {
            Document key = index.get("key", Document.class);
            if (key != null && hasExactAscendingKey(key, fields)) {
                return index;
            }
        }
        return null;
    }

    private Document findByName(List<Document> indexes, String indexName) {
        for (Document index : indexes) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }
        return null;
    }

    private boolean hasExactAscendingKey(Document key, List<String> fields) {
        List<String> keyFields = new ArrayList<>(key.keySet());
        if (!keyFields.equals(fields)) {
            return false;
        }
        for (String field : fields) {
            if (!(key.get(field) instanceof Number direction) || direction.intValue() != 1) {
                return false;
            }
        }
        return true;
    }

    private boolean isUniqueTrue(Document index) {
        Object value = index.get("unique");
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private boolean isSparseTrue(Document index) {
        Object value = index.get("sparse");
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private IllegalStateException incompatible(String collectionName, List<String> fields, boolean unique,
                                               Document found) {
        return new IllegalStateException("Incompatible index '" + found.getString("name") + "' detected on "
                + collectionName + ". Expected " + (unique ? "a unique" : "a non-unique") + " ascending index on "
                + fields + " (no partial filter, not sparse), but found " + found.toJson() + ". "
                + "Automatic drop is disabled for safety. Manual migration required.");
    }
}
