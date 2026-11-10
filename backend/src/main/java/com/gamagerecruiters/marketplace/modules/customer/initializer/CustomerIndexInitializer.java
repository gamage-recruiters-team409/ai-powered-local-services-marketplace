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

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.mongodb.client.MongoCollection;

@Component
@Profile("!test")
public class CustomerIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(CustomerIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public CustomerIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for customer collections...");
        initCustomerAddressesIndex();
        log.info("Customer collection indexes verified successfully.");
    }

    public void initCustomerAddressesIndex() {
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");

            for (Document indexDoc : collection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (key != null && key.containsKey("customerId")) {
                    boolean validKey = key.size() == 1 && isDirectionAscending(key.get("customerId"));
                    boolean isUnique = isUniqueTrue(indexDoc);

                    if (validKey && !isUnique) {
                        log.info("Index verified: customer_addresses.customerId -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on customer_addresses.customerId. " +
                            "Expected key {customerId: 1}, unique: false, but found [key=" + key.toJson() +
                            ", unique=" + isUnique + "]. Automatic drop is disabled for safety."
                    );
                }
            }

            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(CustomerAddress.class).ensureIndex(
                    new Index().on("customerId", Sort.Direction.ASC).named("customerId_1")
            );
            log.info("Index created: customer_addresses.customerId -> {}", result);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index 'customerId_1' on customer_addresses collection.", e
            );
        }
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
