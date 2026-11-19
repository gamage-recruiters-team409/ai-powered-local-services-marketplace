package com.gamagerecruiters.marketplace.modules.customer.initializer;

import java.util.List;

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

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerFavourite;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;

/**
 * Non-destructive MongoDB index initializer for the {@code customer_favourites} collection (FR-CUST-005).
 * Validates or creates the unique compound index on {@code (customerId, type, targetId)}.
 * Follows the non-destructive conventions of {@link CustomerIndexInitializer}.
 */
@Component
@Profile("!test")
public class CustomerFavouriteIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(CustomerFavouriteIndexInitializer.class);

    public static final String COLLECTION_NAME = "customer_favourites";
    public static final String COMPOUND_UNIQUE_INDEX_NAME = "customerId_1_type_1_targetId_1_unique";

    private final MongoTemplate mongoTemplate;

    public CustomerFavouriteIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for customer_favourites collection...");

        ensureCollectionExists();

        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION_NAME);

        preCheckNoDuplicates(collection);
        initCompoundUniqueIndex(collection);

        log.info("customer_favourites collection indexes verified successfully.");
    }

    public void initCompoundUniqueIndex(MongoCollection<Document> collection) {
        try {
            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isCompoundKey(key) || COMPOUND_UNIQUE_INDEX_NAME.equals(indexName)) {
                    boolean validKey = isCompoundKey(key);
                    boolean isUnique = isUniqueTrue(indexDoc);

                    if (validKey && isUnique) {
                        log.info("Index verified: customer_favourites compound unique -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on customer_favourites. " +
                            "Expected unique compound key {customerId: 1, type: 1, targetId: 1}, unique: true, but found [key=" +
                            (key != null ? key.toJson() : "null") + ", unique=" + isUnique + "]. Automatic drop is disabled for safety."
                    );
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(CustomerFavourite.class).ensureIndex(
                    new Index()
                            .on("customerId", Sort.Direction.ASC)
                            .on("type", Sort.Direction.ASC)
                            .on("targetId", Sort.Direction.ASC)
                            .unique()
                            .named(COMPOUND_UNIQUE_INDEX_NAME)
            );
            log.info("Index created: customer_favourites compound unique -> '{}'", result != null ? result : COMPOUND_UNIQUE_INDEX_NAME);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + COMPOUND_UNIQUE_INDEX_NAME + "' on customer_favourites collection.", e
            );
        }
    }

    public void preCheckNoDuplicates(MongoCollection<Document> collection) {
        try {
            List<Document> pipeline = List.of(
                    new Document("$group", new Document("_id",
                            new Document("customerId", "$customerId")
                                    .append("type", "$type")
                                    .append("targetId", "$targetId"))
                            .append("count", new Document("$sum", 1))),
                    new Document("$match", new Document("count", new Document("$gt", 1)))
            );
            Document duplicate = collection.aggregate(pipeline).first();
            if (duplicate != null) {
                Document id = duplicate.get("_id", Document.class);
                Object count = duplicate.get("count");
                throw new IllegalStateException(
                        "Startup failed: Incompatible existing data in 'customer_favourites'. Duplicate target detected: " +
                        (id != null ? id.toJson() : "duplicate entries") + " (count: " + count + "). " +
                        "Automatic cleanup is disabled for safety."
                );
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unable to perform pre-check on customer_favourites (collection may be empty or uninitialized): {}", e.getMessage());
        }
    }

    private void ensureCollectionExists() {
        if (!mongoTemplate.collectionExists(COLLECTION_NAME)) {
            try {
                mongoTemplate.createCollection(COLLECTION_NAME);
                log.info("Created collection '{}'", COLLECTION_NAME);
            } catch (Exception e) {
                if (isNamespaceExistsError(e) && mongoTemplate.collectionExists(COLLECTION_NAME)) {
                    log.info("Collection '{}' was created concurrently by another instance", COLLECTION_NAME);
                } else {
                    throw new IllegalStateException("Startup failed: Unable to create collection '" + COLLECTION_NAME + "'", e);
                }
            }
        }
    }

    private boolean isNamespaceExistsError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof MongoCommandException mce && mce.getErrorCode() == 48) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isCompoundKey(Document key) {
        return key != null && key.size() == 3 &&
                isDirectionAscending(key.get("customerId")) &&
                isDirectionAscending(key.get("type")) &&
                isDirectionAscending(key.get("targetId"));
    }

    private boolean isDirectionAscending(Object dir) {
        if (dir instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private boolean isUniqueTrue(Document indexDoc) {
        Object val = indexDoc.get("unique");
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }
}
