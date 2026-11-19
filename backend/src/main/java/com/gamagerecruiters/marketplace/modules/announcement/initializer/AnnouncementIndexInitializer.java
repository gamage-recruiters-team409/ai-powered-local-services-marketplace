package com.gamagerecruiters.marketplace.modules.announcement.initializer;

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
 * Creates the indexes of the {@code announcements} collection at startup (create-only: an existing identical index
 * is a no-op, a conflicting definition fails startup; nothing is ever dropped).
 * <ul>
 *   <li>{@code createdAt_-1}: the default list, newest first.</li>
 *   <li>{@code status_1_createdAt_-1}: list filtered by status (tabs) and the status counts.</li>
 *   <li>{@code status_1_scheduledAt_1} (partial, status = SCHEDULED): lets the publisher find due announcements.</li>
 * </ul>
 */
@Component
@Profile("!test")
public class AnnouncementIndexInitializer {

    static final String ANNOUNCEMENTS = "announcements";

    private static final Logger log = LoggerFactory.getLogger(AnnouncementIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public AnnouncementIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        try {
            MongoCollection<Document> announcements = mongoTemplate.getCollection(ANNOUNCEMENTS);
            announcements.createIndex(Indexes.descending("createdAt"), new IndexOptions().name("createdAt_-1"));
            announcements.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("createdAt")),
                    new IndexOptions().name("status_1_createdAt_-1"));
            announcements.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("scheduledAt")),
                    new IndexOptions().name("status_1_scheduledAt_1")
                            .partialFilterExpression(new Document("status", "SCHEDULED")));
            log.info("Announcement indexes verified on collection {}", ANNOUNCEMENTS);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Startup failed: unable to create or verify the indexes of the "
                    + ANNOUNCEMENTS + " collection. Check for a conflicting index.", e);
        }
    }
}
