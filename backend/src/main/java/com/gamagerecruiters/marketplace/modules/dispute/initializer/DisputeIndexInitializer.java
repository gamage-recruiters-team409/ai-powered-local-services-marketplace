package com.gamagerecruiters.marketplace.modules.dispute.initializer;

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
 * Creates the indexes of the {@code disputes} collection at startup. Creating an index that already
 * exists with the same definition does nothing; a conflicting definition fails startup (never dropped
 * automatically).
 *
 * <ul>
 *   <li>{@code activeBookingKey_1} (unique, partial): at most one active dispute per booking, also under
 *       concurrent requests. Only documents where activeBookingKey is a string are indexed.</li>
 *   <li>{@code customerId_1_createdAt_-1}, {@code providerId_1_createdAt_-1}: "my disputes".</li>
 *   <li>{@code status_1_createdAt_-1}: admin list filtered by status.</li>
 * </ul>
 */
@Component
@Profile("!test")
public class DisputeIndexInitializer {

    static final String DISPUTES = "disputes";

    private static final Logger log = LoggerFactory.getLogger(DisputeIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public DisputeIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        try {
            MongoCollection<Document> disputes = mongoTemplate.getCollection(DISPUTES);

            disputes.createIndex(Indexes.ascending("activeBookingKey"), new IndexOptions()
                    .name("activeBookingKey_1")
                    .unique(true)
                    .partialFilterExpression(new Document("activeBookingKey", new Document("$type", "string"))));
            disputes.createIndex(Indexes.compoundIndex(Indexes.ascending("customerId"), Indexes.descending("createdAt")),
                    new IndexOptions().name("customerId_1_createdAt_-1"));
            disputes.createIndex(Indexes.compoundIndex(Indexes.ascending("providerId"), Indexes.descending("createdAt")),
                    new IndexOptions().name("providerId_1_createdAt_-1"));
            disputes.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("createdAt")),
                    new IndexOptions().name("status_1_createdAt_-1"));

            log.info("Dispute indexes verified on collection {}", DISPUTES);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Startup failed: unable to create or verify the indexes of the "
                    + DISPUTES + " collection. Check for duplicate active disputes or a conflicting index.", e);
        }
    }
}
