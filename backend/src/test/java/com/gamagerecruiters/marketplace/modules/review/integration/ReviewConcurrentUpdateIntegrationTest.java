package com.gamagerecruiters.marketplace.modules.review.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.UpdateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewAtomicUpdates;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

/**
 * Real MongoDB checks that customer edits and provider replies cannot overwrite each other,
 * and that the edit rules are enforced inside the atomic update itself.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class ReviewConcurrentUpdateIntegrationTest {

    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final int ROUNDS = 20;

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
    private ReviewService reviewService;

    @Autowired
    private ReviewAtomicUpdates reviewUpdates;

    @BeforeEach
    void clean() {
        reviewRepository.deleteAll();
    }

    private Review saveReview(String bookingId, Instant createdAt) {
        Review review = new Review();
        review.setBookingId(bookingId);
        review.setListingId("listing-1");
        review.setProviderId(PROVIDER);
        review.setCustomerId(CUSTOMER);
        review.setRating(4);
        review.setComment("Good");
        review.setStatus(ReviewStatus.PUBLISHED);
        review.setCreatedAt(createdAt);
        review.setUpdatedAt(createdAt);
        return reviewRepository.save(review);
    }

    private Review stored(String reviewId) {
        return reviewRepository.findById(reviewId).orElseThrow();
    }

    @Test
    @DisplayName("Customer edit and provider reply at the same time: nothing is overwritten, never an edit after a reply")
    void concurrentEditAndReplyNeverOverwrite() throws Exception {
        int editsApplied = 0;
        int editsRejected = 0;

        for (int round = 0; round < ROUNDS; round++) {
            String reviewId = saveReview("booking-" + round, Instant.now()).getId();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ReviewResponse> edit = pool.submit(() -> {
                    start.await();
                    return reviewService.updateReview(CUSTOMER, reviewId, new UpdateReviewRequest(2, "Changed"));
                });
                Future<ReviewResponse> reply = pool.submit(() -> {
                    start.await();
                    return reviewService.replyToReview(PROVIDER, reviewId, new ProviderReplyRequest("Thank you"));
                });
                start.countDown();

                reply.get(10, TimeUnit.SECONDS); // the reply always succeeds

                boolean editApplied;
                try {
                    edit.get(10, TimeUnit.SECONDS);
                    editApplied = true;
                } catch (ExecutionException ex) {
                    ApiException api = assertInstanceOf(ApiException.class, ex.getCause());
                    assertEquals(HttpStatus.CONFLICT, api.getStatus());
                    assertEquals("REVIEW_NOT_EDITABLE", api.getErrorCode());
                    editApplied = false;
                }

                Review review = stored(reviewId);
                // The provider's reply is never lost
                assertNotNull(review.getProviderReply());
                assertEquals("Thank you", review.getProviderReply().getText());
                if (editApplied) {
                    // The edit landed before the reply, and the reply did not overwrite it
                    assertEquals(2, review.getRating());
                    assertEquals("Changed", review.getComment());
                    assertTrue(review.isEdited());
                    editsApplied++;
                } else {
                    // The edit was rejected, so the original review is unchanged
                    assertEquals(4, review.getRating());
                    assertEquals("Good", review.getComment());
                    assertFalse(review.isEdited());
                    editsRejected++;
                }
            } finally {
                pool.shutdownNow();
            }
        }

        assertEquals(ROUNDS, editsApplied + editsRejected);
    }

    @Test
    @DisplayName("Stale edit after the provider replied is rejected by MongoDB and changes nothing")
    void staleEditAfterReplyIsRejected() {
        String reviewId = saveReview("booking-stale", Instant.now()).getId();
        reviewService.replyToReview(PROVIDER, reviewId, new ProviderReplyRequest("Thanks"));

        // Same call the service makes after its own checks passed on an older copy
        Instant now = Instant.now();
        Optional<Review> result = reviewUpdates.applyCustomerEdit(reviewId, CUSTOMER, 1, "Stale",
                now.minus(Duration.ofHours(48)), now);

        assertTrue(result.isEmpty());
        Review review = stored(reviewId);
        assertEquals(4, review.getRating());
        assertEquals("Good", review.getComment());
        assertFalse(review.isEdited());
        assertEquals("Thanks", review.getProviderReply().getText());
    }

    @Test
    @DisplayName("Provider reply keeps the customer's earlier edit (only reply fields are written)")
    void replyKeepsEarlierEdit() {
        String reviewId = saveReview("booking-edit-first", Instant.now()).getId();
        reviewService.updateReview(CUSTOMER, reviewId, new UpdateReviewRequest(2, "Changed"));

        ProviderReply reply = new ProviderReply();
        reply.setText("Sorry to hear that");
        reply.setRepliedAt(Instant.now());
        assertTrue(reviewUpdates.applyProviderReply(reviewId, PROVIDER, reply, Instant.now()).isPresent());

        Review review = stored(reviewId);
        assertEquals(2, review.getRating());
        assertEquals("Changed", review.getComment());
        assertTrue(review.isEdited());
        assertEquals("Sorry to hear that", review.getProviderReply().getText());
    }

    @Test
    @DisplayName("Atomic edit does not match a review older than 48 hours or without createdAt")
    void editWindowEnforcedInsideTheUpdate() {
        Instant now = Instant.now();
        String oldId = saveReview("booking-old", now.minus(Duration.ofHours(48)).minusSeconds(60)).getId();
        String noTimeId = saveReview("booking-no-time", null).getId();

        assertTrue(reviewUpdates.applyCustomerEdit(oldId, CUSTOMER, 1, "Late",
                now.minus(Duration.ofHours(48)), now).isEmpty());
        assertTrue(reviewUpdates.applyCustomerEdit(noTimeId, CUSTOMER, 1, "Late",
                now.minus(Duration.ofHours(48)), now).isEmpty());

        assertEquals(4, stored(oldId).getRating());
        assertEquals(4, stored(noTimeId).getRating());
    }
}
