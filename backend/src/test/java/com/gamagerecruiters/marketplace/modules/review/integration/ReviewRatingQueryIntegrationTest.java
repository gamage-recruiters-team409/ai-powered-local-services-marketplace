package com.gamagerecruiters.marketplace.modules.review.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingQuery;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

/** Real MongoDB check of the rating aggregation: only PUBLISHED reviews of the provider count. */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class ReviewRatingQueryIntegrationTest {

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
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewRatingQuery ratingQuery;

    @Autowired
    private ReviewService reviewService;

    @BeforeEach
    void clean() {
        reviewRepository.deleteAll();
    }

    private void save(String bookingId, String providerId, int rating, ReviewStatus status) {
        Review review = new Review();
        review.setBookingId(bookingId);
        review.setListingId("listing-1");
        review.setProviderId(providerId);
        review.setCustomerId("cust-" + bookingId);
        review.setRating(rating);
        review.setStatus(status);
        review.setCreatedAt(Instant.now());
        review.setUpdatedAt(Instant.now());
        reviewRepository.save(review);
    }

    @Test
    @DisplayName("Counts only PUBLISHED reviews of the provider, grouped by rating")
    void countsPublishedByRating() {
        save("b1", "prov-1", 5, ReviewStatus.PUBLISHED);
        save("b2", "prov-1", 5, ReviewStatus.PUBLISHED);
        save("b3", "prov-1", 4, ReviewStatus.PUBLISHED);
        save("b4", "prov-1", 1, ReviewStatus.REMOVED);
        save("b5", "prov-1", 2, ReviewStatus.AWAITING_MODERATION);
        save("b6", "prov-2", 1, ReviewStatus.PUBLISHED);

        Map<Integer, Long> counts = ratingQuery.countPublishedByRating("prov-1");

        assertEquals(Map.of(5, 2L, 4, 1L), counts);

        RatingSummaryResponse summary = reviewService.getRatingSummary("prov-1");
        assertEquals(3, summary.reviewCount());
        assertEquals(4.7, summary.averageRating()); // 14 / 3 = 4.67
    }
}
