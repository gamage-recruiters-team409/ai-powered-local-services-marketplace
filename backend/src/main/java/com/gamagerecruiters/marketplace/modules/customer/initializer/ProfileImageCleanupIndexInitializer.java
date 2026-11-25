package com.gamagerecruiters.marketplace.modules.customer.initializer;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;
import com.mongodb.client.MongoCollection;

/**
 * Non-destructive MongoDB index initializer for the {@code profile_image_cleanup} collection.
 * Ensures the unique index on {@code publicId} and compound query index on {@code (status, createdAt)}.
 */
@Component
@Profile("!test")
public class ProfileImageCleanupIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanupIndexInitializer.class);

    public static final String COLLECTION_NAME = "profile_image_cleanup";
    public static final String PUBLIC_ID_UNIQUE_INDEX_NAME = "publicId_1_unique";
    public static final String STATUS_CREATED_INDEX_NAME = "status_1_createdAt_1";
    public static final String STATUS_NEXT_ATTEMPT_INDEX_NAME = "status_1_nextAttemptAt_1";

    private final MongoTemplate mongoTemplate;

    public ProfileImageCleanupIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for profile_image_cleanup collection...");

        ensureCollectionExists();

        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION_NAME);

        initPublicIdUniqueIndex(collection);
        initStatusCreatedIndex(collection);
        initStatusNextAttemptIndex(collection);

        log.info("profile_image_cleanup collection indexes verified successfully.");
    }

    public void initPublicIdUniqueIndex(MongoCollection<Document> collection) {
        try {
            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isPublicIdKey(key) || PUBLIC_ID_UNIQUE_INDEX_NAME.equals(indexName)) {
                    boolean validKey = isPublicIdKey(key);
                    boolean isUnique = isUniqueTrue(indexDoc);

                    if (validKey && isUnique) {
                        log.info("Index verified: profile_image_cleanup.publicId -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on profile_image_cleanup.publicId. " +
                            "Expected unique key {publicId: 1}, unique: true, but found [key=" +
                            (key != null ? key.toJson() : "null") + ", unique=" + isUnique + "]. Automatic drop is disabled for safety."
                    );
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(ProfileImageCleanupTask.class).ensureIndex(
                    new Index()
                            .on("publicId", Sort.Direction.ASC)
                            .unique()
                            .named(PUBLIC_ID_UNIQUE_INDEX_NAME)
            );
            log.info("Index created: profile_image_cleanup.publicId -> '{}'", result != null ? result : PUBLIC_ID_UNIQUE_INDEX_NAME);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + PUBLIC_ID_UNIQUE_INDEX_NAME + "' on profile_image_cleanup collection.", e
            );
        }
    }

    public void initStatusCreatedIndex(MongoCollection<Document> collection) {
        try {
            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isStatusCreatedKey(key) || STATUS_CREATED_INDEX_NAME.equals(indexName)) {
                    log.info("Index verified: profile_image_cleanup status+createdAt -> existing '{}' matches expected specification.", indexName);
                    return;
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(ProfileImageCleanupTask.class).ensureIndex(
                    new Index()
                            .on("status", Sort.Direction.ASC)
                            .on("createdAt", Sort.Direction.ASC)
                            .named(STATUS_CREATED_INDEX_NAME)
            );
            log.info("Index created: profile_image_cleanup status+createdAt -> '{}'", result != null ? result : STATUS_CREATED_INDEX_NAME);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + STATUS_CREATED_INDEX_NAME + "' on profile_image_cleanup collection.", e
            );
        }
    }

    public void initStatusNextAttemptIndex(MongoCollection<Document> collection) {
        try {
            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isStatusNextAttemptKey(key) || STATUS_NEXT_ATTEMPT_INDEX_NAME.equals(indexName)) {
                    log.info("Index verified: profile_image_cleanup status+nextAttemptAt -> existing '{}' matches expected specification.", indexName);
                    return;
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(ProfileImageCleanupTask.class).ensureIndex(
                    new Index()
                            .on("status", Sort.Direction.ASC)
                            .on("nextAttemptAt", Sort.Direction.ASC)
                            .named(STATUS_NEXT_ATTEMPT_INDEX_NAME)
            );
            log.info("Index created: profile_image_cleanup status+nextAttemptAt -> '{}'", result != null ? result : STATUS_NEXT_ATTEMPT_INDEX_NAME);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + STATUS_NEXT_ATTEMPT_INDEX_NAME + "' on profile_image_cleanup collection.", e
            );
        }
    }

    private boolean isPublicIdKey(Document key) {
        if (key == null) return false;
        return key.size() == 1 && Integer.valueOf(1).equals(key.get("publicId"));
    }

    private boolean isStatusCreatedKey(Document key) {
        if (key == null) return false;
        return key.size() == 2
                && Integer.valueOf(1).equals(key.get("status"))
                && Integer.valueOf(1).equals(key.get("createdAt"));
    }

    private boolean isStatusNextAttemptKey(Document key) {
        if (key == null) return false;
        return key.size() == 2
                && Integer.valueOf(1).equals(key.get("status"))
                && Integer.valueOf(1).equals(key.get("nextAttemptAt"));
    }

    private boolean isUniqueTrue(Document indexDoc) {
        return Boolean.TRUE.equals(indexDoc.getBoolean("unique"));
    }

    private void ensureCollectionExists() {
        if (!mongoTemplate.collectionExists(COLLECTION_NAME)) {
            mongoTemplate.createCollection(COLLECTION_NAME);
            log.info("Created MongoDB collection '{}'.", COLLECTION_NAME);
        }
    }
}
