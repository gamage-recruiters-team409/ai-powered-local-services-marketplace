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
 * Initializes and verifies the unique and lookup indexes for the 'categories' collection.
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
        ensureUniqueIndex(CATEGORIES_COLLECTION, "slug", SLUG_INDEX);
        ensureStandardIndex(CATEGORIES_COLLECTION, "parentId", PARENT_ID_INDEX);
        log.info("All required MongoDB indexes for catalog module verified successfully.");
    }

    void ensureUniqueIndex(String collectionName, String field, String indexName) {
        try {
            ensureCollectionExists(collectionName);
            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());
            boolean indexExists = indexes.stream()
                    .anyMatch(doc -> indexName.equals(doc.getString("name")));

            if (!indexExists) {
                IndexOptions options = new IndexOptions().name(indexName).unique(true);
                collection.createIndex(Indexes.ascending(field), options);
                log.info("Created unique index '{}' on '{}.{}'", indexName, collectionName, field);
            }
        } catch (Exception ex) {
            log.error("Failed to ensure unique index '{}' on '{}.{}': {}", indexName, collectionName, field, ex.getMessage());
        }
    }

    void ensureStandardIndex(String collectionName, String field, String indexName) {
        try {
            ensureCollectionExists(collectionName);
            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());
            boolean indexExists = indexes.stream()
                    .anyMatch(doc -> indexName.equals(doc.getString("name")));

            if (!indexExists) {
                IndexOptions options = new IndexOptions().name(indexName);
                collection.createIndex(Indexes.ascending(field), options);
                log.info("Created index '{}' on '{}.{}'", indexName, collectionName, field);
            }
        } catch (Exception ex) {
            log.error("Failed to ensure standard index '{}' on '{}.{}': {}", indexName, collectionName, field, ex.getMessage());
        }
    }

    void ensureCollectionExists(String collectionName) {
        if (!mongoTemplate.collectionExists(collectionName)) {
            mongoTemplate.createCollection(collectionName);
            log.info("Created missing collection '{}'", collectionName);
        }
    }
}

