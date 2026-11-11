package com.gamagerecruiters.marketplace.modules.customer.initializer;

import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;

@Component
@Profile("!test")
public class CustomerIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(CustomerIndexInitializer.class);

    private static final String COLLECTION_NAME = "customer_addresses";
    private static final String PLAIN_CUSTOMER_INDEX_NAME = "customerId_1";
    private static final String DEFAULT_ADDRESS_INDEX_NAME = "customerId_1_isDefault_1_unique_default";
    private static final String SLOT_INDEX_NAME = "customerId_1_slot_1_unique";

    private final MongoTemplate mongoTemplate;

    public CustomerIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for customer collections...");

        ensureCollectionExists();

        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION_NAME);

        // Pre-checks on existing data before index creation
        preCheckNoDuplicateDefaults(collection);
        preCheckNoDuplicateSlots(collection);

        // Required indexes verified independently
        initPlainCustomerIdIndex(collection);
        initDefaultAddressUniqueIndex(collection);
        initSlotUniqueIndex(collection);

        log.info("Customer collection indexes verified successfully.");
    }

    public void initPlainCustomerIdIndex(MongoCollection<Document> collection) {
        try {
            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isPlainCustomerIdIndex(key) || PLAIN_CUSTOMER_INDEX_NAME.equals(indexName)) {
                    boolean validKey = isPlainCustomerIdIndex(key);
                    boolean isUnique = isUniqueTrue(indexDoc);

                    if (validKey && !isUnique) {
                        log.info("Index verified: customer_addresses.customerId -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on customer_addresses.customerId. " +
                            "Expected key {customerId: 1}, unique: false, but found [key=" + (key != null ? key.toJson() : "null") +
                            ", unique=" + isUnique + "]. Automatic drop is disabled for safety."
                    );
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(CustomerAddress.class).ensureIndex(
                    new Index().on("customerId", Sort.Direction.ASC).named(PLAIN_CUSTOMER_INDEX_NAME)
            );
            log.info("Index created: customer_addresses.customerId -> '{}'", result != null ? result : PLAIN_CUSTOMER_INDEX_NAME);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + PLAIN_CUSTOMER_INDEX_NAME + "' on customer_addresses collection.", e
            );
        }
    }

    public void initDefaultAddressUniqueIndex(MongoCollection<Document> collection) {
        try {
            Document expectedFilter = new Document("isDefault", true);

            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isDefaultAddressKey(key) || DEFAULT_ADDRESS_INDEX_NAME.equals(indexName)) {
                    boolean validKey = isDefaultAddressKey(key);
                    boolean isUnique = isUniqueTrue(indexDoc);
                    Document partialFilter = indexDoc.get("partialFilterExpression", Document.class);
                    boolean validFilter = expectedFilter.equals(partialFilter);

                    if (validKey && isUnique && validFilter) {
                        log.info("Index verified: customer_addresses.default (partial unique) -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on customer_addresses default address. " +
                            "Expected key {customerId: 1, isDefault: 1}, unique: true, partialFilterExpression: {isDefault: true}, " +
                            "but found [key=" + (key != null ? key.toJson() : "null") +
                            ", unique=" + isUnique +
                            ", partialFilterExpression=" + (partialFilter != null ? partialFilter.toJson() : "null") + "]. " +
                            "Automatic drop is disabled for safety."
                    );
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(CustomerAddress.class).ensureIndex(
                    new Index()
                            .on("customerId", Sort.Direction.ASC)
                            .on("isDefault", Sort.Direction.ASC)
                            .unique()
                            .partial(PartialIndexFilter.of(Criteria.where("isDefault").is(true)))
                            .named(DEFAULT_ADDRESS_INDEX_NAME)
            );
            log.info("Index created: customer_addresses.default (partial unique) -> '{}'", result != null ? result : DEFAULT_ADDRESS_INDEX_NAME);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + DEFAULT_ADDRESS_INDEX_NAME + "' on customer_addresses collection.", e
            );
        }
    }

    public void initSlotUniqueIndex(MongoCollection<Document> collection) {
        try {
            Document expectedFilter = new Document("slot", new Document("$gt", 0));

            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (isSlotKey(key) || SLOT_INDEX_NAME.equals(indexName)) {
                    boolean validKey = isSlotKey(key);
                    boolean isUnique = isUniqueTrue(indexDoc);
                    Document partialFilter = indexDoc.get("partialFilterExpression", Document.class);
                    boolean validFilter = expectedFilter.equals(partialFilter);

                    if (validKey && isUnique && validFilter) {
                        log.info("Index verified: customer_addresses.slot (partial unique) -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on customer_addresses slot. " +
                            "Expected key {customerId: 1, slot: 1}, unique: true, partialFilterExpression: {slot: {$gt: 0}}, " +
                            "but found [key=" + (key != null ? key.toJson() : "null") +
                            ", unique=" + isUnique +
                            ", partialFilterExpression=" + (partialFilter != null ? partialFilter.toJson() : "null") + "]. " +
                            "Automatic drop is disabled for safety."
                    );
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(CustomerAddress.class).ensureIndex(
                    new Index()
                            .on("customerId", Sort.Direction.ASC)
                            .on("slot", Sort.Direction.ASC)
                            .unique()
                            .partial(PartialIndexFilter.of(Criteria.where("slot").gt(0)))
                            .named(SLOT_INDEX_NAME)
            );
            log.info("Index created: customer_addresses.slot (partial unique) -> '{}'", result != null ? result : SLOT_INDEX_NAME);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + SLOT_INDEX_NAME + "' on customer_addresses collection.", e
            );
        }
    }

    public void preCheckNoDuplicateDefaults(MongoCollection<Document> collection) {
        try {
            List<Document> pipeline = List.of(
                    new Document("$match", new Document("isDefault", true)),
                    new Document("$group", new Document("_id", "$customerId").append("count", new Document("$sum", 1))),
                    new Document("$match", new Document("count", new Document("$gt", 1)))
            );
            Document duplicate = collection.aggregate(pipeline).first();
            if (duplicate != null) {
                String customerId = duplicate.getString("_id");
                Object count = duplicate.get("count");
                throw new IllegalStateException(
                        "Startup failed: Incompatible existing data in 'customer_addresses'. Customer '" + customerId +
                        "' has " + count + " default addresses (maximum allowed is 1). " +
                        "Automatic cleanup is disabled for safety. " +
                        "Please consult docs/db-migrations/2026-11-customer-default-address-cleanup.md to resolve duplicates before starting."
                );
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unable to perform pre-check on default addresses (collection may be empty or uninitialized): {}", e.getMessage());
        }
    }

    public void preCheckNoDuplicateSlots(MongoCollection<Document> collection) {
        try {
            List<Document> pipeline = List.of(
                    new Document("$match", new Document("slot", new Document("$gt", 0))),
                    new Document("$group", new Document("_id", new Document("customerId", "$customerId").append("slot", "$slot"))
                            .append("count", new Document("$sum", 1))),
                    new Document("$match", new Document("count", new Document("$gt", 1)))
            );
            Document duplicate = collection.aggregate(pipeline).first();
            if (duplicate != null) {
                Document id = duplicate.get("_id", Document.class);
                throw new IllegalStateException(
                        "Startup failed: Incompatible existing data in 'customer_addresses'. Slot conflict detected: " +
                        (id != null ? id.toJson() : "multiple slots") + ". See docs/db-migrations/2026-11-address-slots-migration.md."
                );
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unable to perform pre-check on address slots (collection may be empty or uninitialized): {}", e.getMessage());
        }
    }

    private void ensureCollectionExists() {
        if (!mongoTemplate.collectionExists(COLLECTION_NAME)) {
            try {
                mongoTemplate.createCollection(COLLECTION_NAME);
                log.info("Created collection '{}'", COLLECTION_NAME);
            } catch (Exception e) {
                // NamespaceExists (error code 48) means another instance created it concurrently
                if (isNamespaceExistsError(e) && mongoTemplate.collectionExists(COLLECTION_NAME)) {
                    log.info("Collection '{}' was created concurrently by another instance", COLLECTION_NAME);
                } else {
                    throw new IllegalStateException(
                            "Startup failed: Unable to create collection '" + COLLECTION_NAME + "'", e);
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

    private boolean isPlainCustomerIdIndex(Document key) {
        return key != null && key.size() == 1 && isDirectionAscending(key.get("customerId"));
    }

    private boolean isDefaultAddressKey(Document key) {
        return key != null && key.size() == 2 &&
                isDirectionAscending(key.get("customerId")) &&
                isDirectionAscending(key.get("isDefault"));
    }

    private boolean isSlotKey(Document key) {
        return key != null && key.size() == 2 &&
                isDirectionAscending(key.get("customerId")) &&
                isDirectionAscending(key.get("slot"));
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
