package com.gamagerecruiters.marketplace.modules.catalog.initializer;

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
 * Creates and verifies required MongoDB indexes for the 'categories' collection:
 * unique ascending index on 'slug', and non-unique ascending lookup index on 'parentId'.
 *
 * <p>Ensures the collection exists on fresh databases, inspects the real MongoDB index definitions,
 * checks field names, direction (ascending = 1), uniqueness flag, and ensures indexes are neither
 * sparse nor partial. Fails fast during application startup if an index is incompatible or cannot be created.
 * Existing compatible indexes are preserved without dropping.
 */
@Component
@Profile("!test")
public class CategoryIndexInitializer {

    static final String CATEGORIES_COLLECTION = "categories";
    static final String SLUG_INDEX = "slug_1";
    static final String PARENT_ID_INDEX = "parentId_1";

    private static final Logger log = LoggerFactory.getLogger(CategoryIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public CategoryIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for 'categories' collection...");

        // Ensure the categories collection exists before querying or creating indexes
        ensureCollectionExists(CATEGORIES_COLLECTION);

        // Verify unique ascending index on 'slug'
        ensureIndex(CATEGORIES_COLLECTION, List.of("slug"), SLUG_INDEX, true);

        // Verify non-unique ascending index on 'parentId' for hierarchy lookups
        ensureIndex(CATEGORIES_COLLECTION, List.of("parentId"), PARENT_ID_INDEX, false);

        log.info("All required MongoDB indexes for catalog module verified successfully.");
    }

    /**
     * Verifies that the required index exists and is compatible.
     * If missing, creates it. If incompatible, throws an IllegalStateException to fail fast.
     */
    void ensureIndex(String collectionName, List<String> fields, String indexName, boolean unique) {
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            // Check if an index with the exact key fields and ascending direction already exists
            Document existing = findByKey(indexes, fields);
            if (existing == null) {
                // Check if the index name is already used by an index with different keys
                Document sameName = findByName(indexes, indexName);
                if (sameName != null) {
                    throw incompatible(collectionName, fields, unique, sameName);
                }

                // Create the missing index non-destructively
                collection.createIndex(Indexes.ascending(fields), new IndexOptions().unique(unique).name(indexName));

                // Re-fetch index definitions to verify successful creation
                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByKey(indexes, fields);
                if (existing == null) {
                    throw new IllegalStateException("Startup failed: index '" + indexName + "' on "
                            + collectionName + " was not found after creation.");
                }
            }

            // Verify that uniqueness matches and index is neither partial nor sparse
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

    /**
     * Creates the collection on a fresh database so listIndexes() never runs on a missing namespace.
     */
    void ensureCollectionExists(String collectionName) {
        try {
            if (mongoTemplate.collectionExists(collectionName)) {
                return;
            }
            try {
                mongoTemplate.createCollection(collectionName);
                log.info("Collection created: {}", collectionName);
            } catch (RuntimeException e) {
                // Handle concurrent creation safely
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
