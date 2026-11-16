package com.gamagerecruiters.marketplace.modules.admin.initializer;

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
 * Creates the indexes of the {@code admin_audit_logs} collection for the newest-first list and its filters.
 * Creating an existing identical index does nothing; a conflicting one fails startup (never dropped).
 */
@Component
@Profile("!test")
public class AdminAuditLogIndexInitializer {

    static final String COLLECTION = "admin_audit_logs";

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public AdminAuditLogIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        try {
            MongoCollection<Document> logs = mongoTemplate.getCollection(COLLECTION);
            logs.createIndex(Indexes.descending("createdAt"), new IndexOptions().name("createdAt_-1"));
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("actorId"), Indexes.descending("createdAt")),
                    new IndexOptions().name("actorId_1_createdAt_-1"));
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("targetUserId"), Indexes.descending("createdAt")),
                    new IndexOptions().name("targetUserId_1_createdAt_-1"));
            logs.createIndex(Indexes.compoundIndex(Indexes.ascending("action"), Indexes.descending("createdAt")),
                    new IndexOptions().name("action_1_createdAt_-1"));
            log.info("Admin audit log indexes verified on collection {}", COLLECTION);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Startup failed: unable to create or verify the indexes of the "
                    + COLLECTION + " collection.", e);
        }
    }
}
