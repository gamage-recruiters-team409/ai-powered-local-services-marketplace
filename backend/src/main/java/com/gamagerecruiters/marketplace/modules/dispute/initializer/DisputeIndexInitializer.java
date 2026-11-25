package com.gamagerecruiters.marketplace.modules.dispute.initializer;

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
 * Creates the indexes of the {@code disputes} collection at startup. Creating an index that already
 * exists with the same definition does nothing; a conflicting definition fails startup (never dropped
 * automatically).
 *
 * <ul>
 *   <li>{@code activeBookingKey_1} (unique, partial): at most one active dispute per booking, also under
 *       concurrent requests. Only documents where activeBookingKey is a string are indexed.</li>
 *   <li>{@code customerId_1_createdAt_-1}, {@code providerId_1_createdAt_-1}: "my disputes".</li>
 *   <li>{@code status_1_createdAt_-1}: admin list filtered by status.</li>
 *   <li>{@code bookingSyncState_1_createdAt_1} (partial, PENDING only): lets the booking-sync reconciler find
 *       unfinished intent records without scanning the collection. Holds only the few PENDING records.</li>
 *   <li>{@code status_1_appealDeadline_1} (partial, only documents with an appealDeadline): lets the finalizer find
 *       RESOLVED disputes whose appeal window has passed.</li>
 *   <li>{@code settlement.state_1_settlement.lastAttemptAt_1} (partial, only final disputes): lets the finalizer find
 *       due settlements. Every branch of its claim query starts with {@code settlement.state} (PENDING; FAILED with
 *       nextAttemptAt; NOT_APPLIED with lastAttemptAt), so this index covers it; the terminal states (APPLIED,
 *       REJECTED, MANUAL_REVIEW) are skipped by the state key. The admin summary's manual-review count uses it too.</li>
 *   <li>{@code bookingId_1}: one dispute per booking in any status (the raise check also covers final disputes).</li>
 * </ul>
 * It also indexes the {@code dispute_evidence_cleanup_tasks} collection:
 * <ul>
 *   <li>{@code publicId_1} (unique): one cleanup task per stored asset, so enqueue can upsert without duplicating a
 *       task and the reconciler never races two tasks for the same asset.</li>
 * </ul>
 * After creating them, startup checks that every index above exists and fails otherwise.
 */
@Component
@Profile("!test")
public class DisputeIndexInitializer {

    static final String DISPUTES = "disputes";
    static final String EVIDENCE_CLEANUP_TASKS = "dispute_evidence_cleanup_tasks";

    /** Every index this initializer creates on {@code disputes}; all must exist after startup. */
    static final List<String> EXPECTED_INDEXES = List.of("activeBookingKey_1", "customerId_1_createdAt_-1",
            "providerId_1_createdAt_-1", "status_1_createdAt_-1", "bookingSyncState_1_createdAt_1",
            "status_1_appealDeadline_1", "settlement.state_1_settlement.lastAttemptAt_1", "bookingId_1");

    /** Every index this initializer creates on {@code dispute_evidence_cleanup_tasks}; all must exist after startup. */
    static final List<String> EXPECTED_CLEANUP_INDEXES = List.of("publicId_1");

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
            disputes.createIndex(Indexes.compoundIndex(Indexes.ascending("bookingSyncState"), Indexes.ascending("createdAt")),
                    new IndexOptions().name("bookingSyncState_1_createdAt_1")
                            .partialFilterExpression(new Document("bookingSyncState", "PENDING")));
            disputes.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("appealDeadline")),
                    new IndexOptions().name("status_1_appealDeadline_1")
                            .partialFilterExpression(new Document("appealDeadline", new Document("$exists", true))));
            disputes.createIndex(Indexes.compoundIndex(Indexes.ascending("settlement.state"),
                            Indexes.ascending("settlement.lastAttemptAt")),
                    new IndexOptions().name("settlement.state_1_settlement.lastAttemptAt_1")
                            .partialFilterExpression(new Document("settlement.state", new Document("$exists", true))));
            disputes.createIndex(Indexes.ascending("bookingId"), new IndexOptions().name("bookingId_1"));

            List<String> present = new ArrayList<>();
            disputes.listIndexes().forEach(index -> present.add(index.getString("name")));
            List<String> missing = EXPECTED_INDEXES.stream().filter(name -> !present.contains(name)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing dispute indexes: " + missing);
            }
            log.info("Dispute indexes verified on collection {}: {}", DISPUTES, EXPECTED_INDEXES);

            MongoCollection<Document> cleanupTasks = mongoTemplate.getCollection(EVIDENCE_CLEANUP_TASKS);
            cleanupTasks.createIndex(Indexes.ascending("publicId"),
                    new IndexOptions().name("publicId_1").unique(true));
            List<String> cleanupPresent = new ArrayList<>();
            cleanupTasks.listIndexes().forEach(index -> cleanupPresent.add(index.getString("name")));
            List<String> cleanupMissing = EXPECTED_CLEANUP_INDEXES.stream()
                    .filter(name -> !cleanupPresent.contains(name)).toList();
            if (!cleanupMissing.isEmpty()) {
                throw new IllegalStateException("Missing evidence cleanup indexes: " + cleanupMissing);
            }
            log.info("Dispute indexes verified on collection {}: {}", EVIDENCE_CLEANUP_TASKS, EXPECTED_CLEANUP_INDEXES);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Startup failed: unable to create or verify the indexes of the "
                    + DISPUTES + " collection. Check for duplicate active disputes or a conflicting index.", e);
        }
    }
}
