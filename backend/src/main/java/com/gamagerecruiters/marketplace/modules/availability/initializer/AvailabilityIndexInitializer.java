package com.gamagerecruiters.marketplace.modules.availability.initializer;

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
 * Creates and verifies the unique indexes the availability module depends on.
 *
 * <p>On a fresh database the collections may not exist yet, so each collection is created
 * first when missing. For each index it then reads the real definition from MongoDB and checks the key fields,
 * their order, ascending direction and {@code unique: true}. Startup fails when an
 * incompatible index exists. Existing indexes are never dropped automatically.
 */
@Component
@Profile("!test")
public class AvailabilityIndexInitializer {

    static final String AVAILABILITY_RULES = "availability_rules";
    static final String BLOCKED_DATES = "blocked_dates";
    static final String PROVIDER_INDEX = "providerId_1";
    static final String PROVIDER_DATE_INDEX = "providerId_1_date_1";

    private static final Logger log = LoggerFactory.getLogger(AvailabilityIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public AvailabilityIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for availability collections...");

        ensureUniqueIndex(AVAILABILITY_RULES, List.of("providerId"), PROVIDER_INDEX);
        ensureUniqueIndex(BLOCKED_DATES, List.of("providerId", "date"), PROVIDER_DATE_INDEX);

        log.info("All required MongoDB indexes for availability module verified successfully.");
    }

    void ensureUniqueIndex(String collectionName, List<String> fields, String indexName) {
        try {
            ensureCollectionExists(collectionName);

            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByKey(indexes, fields);
            if (existing == null) {
                Document sameName = findByName(indexes, indexName);
                if (sameName != null) {
                    throw incompatible(collectionName, fields, sameName);
                }

                collection.createIndex(Indexes.ascending(fields), new IndexOptions().unique(true).name(indexName));

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByKey(indexes, fields);
                if (existing == null) {
                    throw new IllegalStateException("Startup failed: index '" + indexName + "' on "
                            + collectionName + " was not found after creation.");
                }
            }

            if (!isUniqueTrue(existing) || existing.containsKey("partialFilterExpression")) {
                throw incompatible(collectionName, fields, existing);
            }

            log.info("Index verified: {} -> {}", collectionName, existing.toJson());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Startup failed: Unable to create or verify required unique index '"
                    + indexName + "' on " + collectionName + " collection. "
                    + "Ensure no duplicate values exist for " + fields + ".", e);
        }
    }

    /**
     * Creates the collection when it does not exist yet (fresh database), so listIndexes()
     * never runs against a missing namespace. If another instance creates it at the same
     * moment, the existing collection is accepted.
     */
    private void ensureCollectionExists(String collectionName) {
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
    }

    /** Finds the index whose key is exactly the given fields, in the same order, all ascending. */
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

    private IllegalStateException incompatible(String collectionName, List<String> fields, Document found) {
        return new IllegalStateException("Incompatible index '" + found.getString("name") + "' detected on "
                + collectionName + ". Expected a unique ascending index on " + fields
                + " (no partial filter), but found " + found.toJson() + ". "
                + "Automatic drop is disabled for safety. Manual migration required.");
    }
}
