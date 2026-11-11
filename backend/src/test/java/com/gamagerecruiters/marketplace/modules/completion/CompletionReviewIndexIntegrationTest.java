package com.gamagerecruiters.marketplace.modules.completion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.initializer.CompletionIndexInitializer;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.initializer.ReviewIndexInitializer;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;

/**
 * Runs the real index initializers against a Testcontainers MongoDB, then checks the
 * stored index definitions and that duplicate bookingId values are rejected. Also
 * re-runs the initializers after dropping the collections to prove that a fresh
 * database (collection not yet created) initializes and verifies correctly.
 * Skipped only when SKIP_DOCKER_TESTS=true (same switch as the auth integration test).
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class CompletionReviewIndexIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CompletionReviewIndexIntegrationTest.class);

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer != null && mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ServiceCompletionRepository serviceCompletionRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private CompletionIndexInitializer completionIndexInitializer;

    @Autowired
    private ReviewIndexInitializer reviewIndexInitializer;

    @BeforeEach
    void cleanCollections() {
        // deleteAll removes documents only; the indexes stay in place
        serviceCompletionRepository.deleteAll();
        reviewRepository.deleteAll();
    }

    @Test
    @DisplayName("service_completions stores bookingId_1 as {bookingId: 1}, unique: true")
    void serviceCompletionsBookingIdIndexIsStoredAsUnique() {
        assertStoredUniqueBookingIdIndex("service_completions");
    }

    @Test
    @DisplayName("reviews stores bookingId_1 as {bookingId: 1}, unique: true")
    void reviewsBookingIdIndexIsStoredAsUnique() {
        assertStoredUniqueBookingIdIndex("reviews");
    }

    @Test
    @DisplayName("service_completions rejects a second record with the same bookingId")
    void serviceCompletionsRejectsDuplicateBookingId() {
        mongoTemplate.insert(completion("booking-duplicate-test"));

        assertThrows(DuplicateKeyException.class,
                () -> mongoTemplate.insert(completion("booking-duplicate-test")));
        assertEquals(1, serviceCompletionRepository.count());
        log.info("Duplicate bookingId rejected by service_completions.bookingId_1 (DuplicateKeyException)");
    }

    @Test
    @DisplayName("reviews rejects a second review with the same bookingId")
    void reviewsRejectsDuplicateBookingId() {
        mongoTemplate.insert(review("booking-duplicate-test"));

        assertThrows(DuplicateKeyException.class,
                () -> mongoTemplate.insert(review("booking-duplicate-test")));
        assertEquals(1, reviewRepository.count());
        log.info("Duplicate bookingId rejected by reviews.bookingId_1 (DuplicateKeyException)");
    }

    @Test
    @DisplayName("Fresh database: service_completions is created, indexed and verified when it does not exist")
    void freshServiceCompletionsCollectionIsInitialized() {
        mongoTemplate.dropCollection("service_completions");
        assertFalse(mongoTemplate.collectionExists("service_completions"), "precondition: collection must not exist");

        assertDoesNotThrow(() -> completionIndexInitializer.initializeIndexes());

        assertTrue(mongoTemplate.collectionExists("service_completions"));
        assertStoredUniqueBookingIdIndex("service_completions");
        mongoTemplate.insert(completion("booking-fresh-test"));
        assertThrows(DuplicateKeyException.class, () -> mongoTemplate.insert(completion("booking-fresh-test")));
        log.info("Fresh collection initialization verified: service_completions created, bookingId_1 unique, duplicate rejected");
    }

    @Test
    @DisplayName("Fresh database: reviews is created, indexed and verified when it does not exist")
    void freshReviewsCollectionIsInitialized() {
        mongoTemplate.dropCollection("reviews");
        assertFalse(mongoTemplate.collectionExists("reviews"), "precondition: collection must not exist");

        assertDoesNotThrow(() -> reviewIndexInitializer.initializeIndexes());

        assertTrue(mongoTemplate.collectionExists("reviews"));
        assertStoredUniqueBookingIdIndex("reviews");
        mongoTemplate.insert(review("booking-fresh-test"));
        assertThrows(DuplicateKeyException.class, () -> mongoTemplate.insert(review("booking-fresh-test")));
        log.info("Fresh collection initialization verified: reviews created, bookingId_1 unique, duplicate rejected");
    }

    // ----------------------------------------------------------------- helpers

    private void assertStoredUniqueBookingIdIndex(String collectionName) {
        Document stored = null;
        for (Document indexDoc : mongoTemplate.getCollection(collectionName).listIndexes()) {
            log.info("Stored index on {}: {}", collectionName, indexDoc.toJson());
            if ("bookingId_1".equals(indexDoc.getString("name"))) {
                stored = indexDoc;
            }
        }
        assertNotNull(stored, "bookingId_1 index missing on " + collectionName);
        assertEquals(new Document("bookingId", 1), stored.get("key", Document.class));
        assertEquals(Boolean.TRUE, stored.getBoolean("unique"));
        assertTrue(!stored.containsKey("partialFilterExpression"), "bookingId_1 must not be partial");
    }

    private static ServiceCompletion completion(String bookingId) {
        ServiceCompletion completion = new ServiceCompletion();
        completion.setBookingId(bookingId);
        completion.setCustomerId("customer-1");
        completion.setProviderId("provider-1");
        completion.setStartedAt(Instant.now());
        completion.setCreatedAt(Instant.now());
        completion.setUpdatedAt(Instant.now());
        return completion;
    }

    private static Review review(String bookingId) {
        Review review = new Review();
        review.setBookingId(bookingId);
        review.setListingId("listing-1");
        review.setProviderId("provider-1");
        review.setCustomerId("customer-1");
        review.setRating(5);
        review.setComment("Integration test review");
        review.setStatus(ReviewStatus.PUBLISHED);
        review.setCreatedAt(Instant.now());
        review.setUpdatedAt(Instant.now());
        return review;
    }
}
