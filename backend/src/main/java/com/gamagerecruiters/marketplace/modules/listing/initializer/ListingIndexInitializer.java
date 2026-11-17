package com.gamagerecruiters.marketplace.modules.listing.initializer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.bson.Document;
import org.bson.conversions.Bson;
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
 * Creates and validates required MongoDB indexes on the {@code service_listings} collection:
 * compound {@code (providerId, status)}, {@code (categoryId, status)}, {@code (status, createdAt)},
 * and geospatial {@code location_2dsphere}.
 *
 * <p>Follows the strict fail-fast startup standard: existing incompatible indexes are never
 * dropped automatically and always abort startup with an {@link IllegalStateException}.
 */
@Component
@Profile("!test")
public class ListingIndexInitializer {

    static final String SERVICE_LISTINGS = "service_listings";
    static final String PROVIDER_STATUS_INDEX = "providerId_1_status_1";
    static final String CATEGORY_STATUS_INDEX = "categoryId_1_status_1";
    static final String STATUS_CREATED_INDEX = "status_1_createdAt_1";
    static final String LOCATION_2DSPHERE_INDEX = "location_2dsphere";

    private static final Logger log = LoggerFactory.getLogger(ListingIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public ListingIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for {} collection...", SERVICE_LISTINGS);

        ensureCollectionExists(SERVICE_LISTINGS);
        ensureAscendingIndex(SERVICE_LISTINGS, List.of("providerId", "status"), PROVIDER_STATUS_INDEX, false);
        ensureAscendingIndex(SERVICE_LISTINGS, List.of("categoryId", "status"), CATEGORY_STATUS_INDEX, false);
        ensureAscendingIndex(SERVICE_LISTINGS, List.of("status", "createdAt"), STATUS_CREATED_INDEX, false);
        ensureGeo2dsphereIndex(SERVICE_LISTINGS, "location", LOCATION_2DSPHERE_INDEX);

        log.info("All required MongoDB indexes for listing module verified successfully.");
    }

    void ensureAscendingIndex(String collectionName, List<String> fields, String indexName, boolean unique) {
        ensureAndVerifyIndex(
                collectionName,
                indexName,
                unique,
                "an ascending index on " + fields,
                key -> hasExactAscendingKey(key, fields),
                Indexes.ascending(fields)
        );
    }

    void ensureGeo2dsphereIndex(String collectionName, String field, String indexName) {
        ensureAndVerifyIndex(
                collectionName,
                indexName,
                false,
                "a 2dsphere index on [" + field + "]",
                key -> hasExact2dsphereKey(key, field),
                Indexes.geo2dsphere(field)
        );
    }

    private void ensureAndVerifyIndex(
            String collectionName,
            String indexName,
            boolean unique,
            String expectedDescription,
            Predicate<Document> keyMatcher,
            Bson indexKeys
    ) {
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByKeyMatcher(indexes, keyMatcher);
            if (existing == null) {
                Document sameName = findByName(indexes, indexName);
                if (sameName != null) {
                    throw incompatible(collectionName, expectedDescription, unique, sameName);
                }

                IndexOptions options = new IndexOptions().name(indexName);
                if (unique) {
                    options.unique(true);
                }
                collection.createIndex(indexKeys, options);

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByKeyMatcher(indexes, keyMatcher);
                if (existing == null) {
                    throw new IllegalStateException("Startup failed: index '" + indexName + "' on "
                            + collectionName + " was not found after creation.");
                }
            }

            if (isUniqueTrue(existing) != unique
                    || existing.containsKey("partialFilterExpression")
                    || isSparseTrue(existing)) {
                throw incompatible(collectionName, expectedDescription, unique, existing);
            }

            log.info("Index verified: {} -> {}", collectionName, existing.toJson());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Startup failed: Unable to create or verify required index '"
                    + indexName + "' on " + collectionName + " collection.", e);
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

    private Document findByKeyMatcher(List<Document> indexes, Predicate<Document> keyMatcher) {
        for (Document index : indexes) {
            Document key = index.get("key", Document.class);
            if (key != null && keyMatcher.test(key)) {
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

    private boolean hasExact2dsphereKey(Document key, String field) {
        List<String> keyFields = new ArrayList<>(key.keySet());
        return keyFields.equals(List.of(field)) && "2dsphere".equals(key.get(field));
    }

    private boolean isUniqueTrue(Document index) {
        return isFlagTrue(index, "unique");
    }

    private boolean isSparseTrue(Document index) {
        return isFlagTrue(index, "sparse");
    }

    private boolean isFlagTrue(Document index, String key) {
        Object value = index.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private IllegalStateException incompatible(
            String collectionName,
            String expectedDescription,
            boolean unique,
            Document found
    ) {
        return new IllegalStateException("Incompatible index '" + found.getString("name") + "' detected on "
                + collectionName + ". Expected " + (unique ? "unique " : "non-unique ")
                + expectedDescription + " (no partial filter, not sparse), but found " + found.toJson() + ". "
                + "Automatic drop is disabled for safety. Manual migration required.");
    }
}
