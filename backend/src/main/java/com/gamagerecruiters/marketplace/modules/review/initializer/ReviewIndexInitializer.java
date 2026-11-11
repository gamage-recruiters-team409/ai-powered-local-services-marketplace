package com.gamagerecruiters.marketplace.modules.review.initializer;

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

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.mongodb.client.MongoCollection;

/**
 * Creates and verifies the MongoDB indexes required by the review module.
 *
 * <p>The one-review-per-booking rule depends on a unique index on
 * {@code reviews.bookingId}. The stored index definition is read back from MongoDB
 * and checked (key, direction, unique, not partial/sparse) before it is reported as
 * valid. A missing collection is created first (fresh database). An incompatible
 * existing index stops startup; it is never dropped or replaced automatically.
 */
@Component
@Profile("!test")
public class ReviewIndexInitializer {

    static final String COLLECTION = "reviews";
    static final String BOOKING_ID_FIELD = "bookingId";
    static final String BOOKING_ID_INDEX = "bookingId_1";
    static final String PROVIDER_STATUS_CREATED_INDEX = "providerId_1_status_1_createdAt_-1";

    private static final Logger log = LoggerFactory.getLogger(ReviewIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public ReviewIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for {}...", COLLECTION);

        initBookingIdUniqueIndex();
        initProviderStatusCreatedAtIndex();

        log.info("All required MongoDB indexes for {} verified successfully.", COLLECTION);
    }

    private void initBookingIdUniqueIndex() {
        try {
            // Fresh database: create the collection first so its indexes can be read safely
            ensureCollectionExists();

            MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);

            // Inspect existing indexes first — never drop, only validate
            Document bookingIndex = findBookingIdIndex(collection);

            if (bookingIndex == null) {
                createBookingIdIndex();
                // Read the stored definition back instead of trusting the create call
                bookingIndex = findBookingIdIndex(collection);
                if (bookingIndex == null) {
                    throw new IllegalStateException(
                            "Startup failed: required unique index '" + BOOKING_ID_INDEX + "' was requested on "
                                    + COLLECTION + " but was not found when the stored indexes were read back.");
                }
            }

            assertUniqueBookingIdIndex(bookingIndex);
            log.info("Index verified: {}.bookingId (unique) -> stored definition {}", COLLECTION, describe(bookingIndex));
        } catch (IllegalStateException e) {
            throw e; // Re-throw our own validation errors
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create or verify required unique index '" + BOOKING_ID_INDEX + "' on "
                            + COLLECTION + ". Ensure no booking has more than one review.", e);
        }
    }

    private void initProviderStatusCreatedAtIndex() {
        try {
            @SuppressWarnings("removal")
            String name = mongoTemplate.indexOps(Review.class).ensureIndex(
                    new Index()
                            .on("providerId", Sort.Direction.ASC)
                            .on("status", Sort.Direction.ASC)
                            .on("createdAt", Sort.Direction.DESC)
                            .named(PROVIDER_STATUS_CREATED_INDEX)
            );
            log.info("Index ensured: {}.providerId+status+createdAt -> {}", COLLECTION, name);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required index '" + PROVIDER_STATUS_CREATED_INDEX + "' on "
                            + COLLECTION + ".", e);
        }
    }

    /**
     * Makes sure the collection exists before its indexes are read, so a fresh database
     * never depends on listIndexes() behaviour for a missing namespace. Only creates;
     * never drops or modifies an existing collection.
     */
    private void ensureCollectionExists() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            return;
        }
        try {
            mongoTemplate.createCollection(COLLECTION);
            log.info("Collection {} did not exist - created it before index verification", COLLECTION);
        } catch (Exception e) {
            // Another application instance may have created it at the same moment
            if (!mongoTemplate.collectionExists(COLLECTION)) {
                throw new IllegalStateException(
                        "Startup failed: unable to create required collection '" + COLLECTION + "'.", e);
            }
            log.info("Collection {} was created concurrently - continuing with index verification", COLLECTION);
        }
    }

    @SuppressWarnings("removal")
    private void createBookingIdIndex() {
        String name = mongoTemplate.indexOps(Review.class).ensureIndex(
                new Index().on(BOOKING_ID_FIELD, Sort.Direction.ASC).unique().named(BOOKING_ID_INDEX)
        );
        log.info("Index created: {}.bookingId (unique) -> {}", COLLECTION, name);
    }

    /** Returns the index that is on bookingId alone, or that uses the bookingId_1 name; null if none. */
    private Document findBookingIdIndex(MongoCollection<Document> collection) {
        for (Document indexDoc : collection.listIndexes()) {
            Document key = indexDoc.get("key", Document.class);
            boolean onBookingIdOnly = key != null && key.size() == 1 && key.containsKey(BOOKING_ID_FIELD);
            boolean usesExpectedName = BOOKING_ID_INDEX.equals(indexDoc.getString("name"));
            if (onBookingIdOnly || usesExpectedName) {
                return indexDoc;
            }
        }
        return null;
    }

    private void assertUniqueBookingIdIndex(Document indexDoc) {
        Document key = indexDoc.get("key", Document.class);
        boolean validKey = key != null && key.size() == 1 && isAscending(key.get(BOOKING_ID_FIELD));
        boolean validUnique = isTrue(indexDoc.get("unique"));
        boolean coversAllDocuments = !indexDoc.containsKey("partialFilterExpression") && !isTrue(indexDoc.get("sparse"));

        if (validKey && validUnique && coversAllDocuments) {
            return;
        }

        throw new IllegalStateException(
                "Incompatible index '" + indexDoc.getString("name") + "' detected on " + COLLECTION + ".bookingId. "
                        + "Expected key {bookingId: 1} with unique: true (not partial, not sparse), but found "
                        + describe(indexDoc) + ". Automatic drop is disabled for safety. Manual migration required: "
                        + "inspect with db." + COLLECTION + ".getIndexes(), resolve any duplicate bookingId values, "
                        + "then replace the index manually before restarting the application.");
    }

    private static boolean isAscending(Object direction) {
        return direction instanceof Number n && n.doubleValue() == 1.0;
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private static String describe(Document indexDoc) {
        Document summary = new Document("name", indexDoc.getString("name"))
                .append("key", indexDoc.get("key"))
                .append("unique", indexDoc.containsKey("unique") ? indexDoc.get("unique") : "absent");
        if (indexDoc.containsKey("partialFilterExpression")) {
            summary.append("partialFilterExpression", indexDoc.get("partialFilterExpression"));
        }
        if (indexDoc.containsKey("sparse")) {
            summary.append("sparse", indexDoc.get("sparse"));
        }
        return summary.toJson();
    }
}
