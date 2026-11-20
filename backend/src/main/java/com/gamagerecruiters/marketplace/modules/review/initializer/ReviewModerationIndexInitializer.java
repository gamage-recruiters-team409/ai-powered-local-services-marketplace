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
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewReport;

/**
 * Indexes for review moderation (FR-REV-003) and listing ratings (FR-REV-004).
 *
 * <ul>
 *   <li>{@code review_reports}: unique {reviewId, reporterId} (one report per user per review, read back and
 *       verified; an incompatible index stops startup and is never dropped) and {reviewId, status}.</li>
 *   <li>{@code reviews}: {status, createdAt} for the moderation queue and {listingId, status} for listing
 *       ratings.</li>
 * </ul>
 * The rating stats collection uses only its {@code _id}. Collections are created when missing.
 */
@Component
@Profile("!test")
public class ReviewModerationIndexInitializer {

    static final String REPORTS = "review_reports";
    static final String REPORT_UNIQUE_INDEX = "reviewId_1_reporterId_1";
    static final String REPORT_STATUS_INDEX = "reviewId_1_status_1";
    static final String REVIEW_QUEUE_INDEX = "status_1_createdAt_1";
    static final String REVIEW_LISTING_INDEX = "listingId_1_status_1";

    private static final Logger log = LoggerFactory.getLogger(ReviewModerationIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public ReviewModerationIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @SuppressWarnings("removal")
    public void initializeIndexes() {
        try {
            ensureCollectionExists(REPORTS);
            mongoTemplate.indexOps(ReviewReport.class).ensureIndex(new Index()
                    .on("reviewId", Sort.Direction.ASC)
                    .on("reporterId", Sort.Direction.ASC)
                    .unique()
                    .named(REPORT_UNIQUE_INDEX));
            verifyUnique(REPORTS, REPORT_UNIQUE_INDEX);
            mongoTemplate.indexOps(ReviewReport.class).ensureIndex(new Index()
                    .on("reviewId", Sort.Direction.ASC)
                    .on("status", Sort.Direction.ASC)
                    .named(REPORT_STATUS_INDEX));

            mongoTemplate.indexOps(Review.class).ensureIndex(new Index()
                    .on("status", Sort.Direction.ASC)
                    .on("createdAt", Sort.Direction.ASC)
                    .named(REVIEW_QUEUE_INDEX));
            mongoTemplate.indexOps(Review.class).ensureIndex(new Index()
                    .on("listingId", Sort.Direction.ASC)
                    .on("status", Sort.Direction.ASC)
                    .named(REVIEW_LISTING_INDEX));
            log.info("Review moderation and listing rating indexes verified");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: unable to create or verify the review moderation indexes on " + REPORTS + " / reviews.", e);
        }
    }

    private void ensureCollectionExists(String collection) {
        if (mongoTemplate.collectionExists(collection)) {
            return;
        }
        try {
            mongoTemplate.createCollection(collection);
        } catch (Exception e) {
            // Another instance may have created it at the same moment
            if (!mongoTemplate.collectionExists(collection)) {
                throw new IllegalStateException("Startup failed: unable to create collection '" + collection + "'.", e);
            }
        }
    }

    /** Reads the stored index back: it must be unique and cover all documents (not partial, not sparse). */
    private void verifyUnique(String collection, String indexName) {
        for (Document index : mongoTemplate.getCollection(collection).listIndexes()) {
            if (indexName.equals(index.getString("name"))) {
                boolean unique = Boolean.TRUE.equals(index.get("unique"));
                boolean coversAll = !index.containsKey("partialFilterExpression") && !Boolean.TRUE.equals(index.get("sparse"));
                if (unique && coversAll) {
                    return;
                }
                throw new IllegalStateException("Incompatible index '" + indexName + "' on " + collection
                        + ": expected unique and not partial/sparse, found " + index.toJson()
                        + ". It is never dropped automatically; replace it manually and restart.");
            }
        }
        throw new IllegalStateException("Startup failed: required unique index '" + indexName + "' was not found on "
                + collection + " after creating it.");
    }
}
