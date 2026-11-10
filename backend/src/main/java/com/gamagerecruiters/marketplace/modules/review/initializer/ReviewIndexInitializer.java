package com.gamagerecruiters.marketplace.modules.review.initializer;

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

@Component
@Profile("!test")
public class ReviewIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(ReviewIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public ReviewIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @SuppressWarnings("removal")
    public void initializeIndexes() {
        try {
            String bookingIndex = mongoTemplate.indexOps(Review.class).ensureIndex(
                    new Index().on("bookingId", Sort.Direction.ASC).unique().named("bookingId_1")
            );
            log.info("Index verified: reviews.bookingId (unique) -> {}", bookingIndex);

            String providerIndex = mongoTemplate.indexOps(Review.class).ensureIndex(
                    new Index()
                            .on("providerId", Sort.Direction.ASC)
                            .on("status", Sort.Direction.ASC)
                            .on("createdAt", Sort.Direction.DESC)
                            .named("providerId_1_status_1_createdAt_-1")
            );
            log.info("Index verified: reviews.providerId+status+createdAt -> {}", providerIndex);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required indexes on reviews collection. " +
                    "Ensure no booking has more than one review.", e
            );
        }
    }
}
