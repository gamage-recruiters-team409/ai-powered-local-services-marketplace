package com.gamagerecruiters.marketplace.modules.review.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationAction;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;
import com.gamagerecruiters.marketplace.modules.review.domain.RemovalReason;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportReason;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportStatus;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewRatingStats;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewReport;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.PublishReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;
import com.gamagerecruiters.marketplace.modules.review.dto.RemoveReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ReportReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.UpdateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.moderation.ModerationQueueFilter;
import com.gamagerecruiters.marketplace.modules.review.moderation.ReviewModerationService;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingFacade;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingStatsService;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

/**
 * Real MongoDB (replica set, transactions) checks for review moderation (FR-REV-003) and the stored
 * rating aggregates (FR-REV-004): reports, the queue, moderator decisions, report resolution,
 * concurrent decisions, re-checking edits, and rating recalculation.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class ReviewModerationIntegrationTest {

    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final String LISTING = "listing-1";
    private static final String MODERATOR = "mod-1";
    private static final int ROUNDS = 10;

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
    private MongoTemplate mongoTemplate;

    @Autowired
    private ReviewModerationService moderationService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewRatingStatsService ratingStats;

    @Autowired
    private ReviewRatingFacade ratingFacade;

    @BeforeEach
    void clean() {
        reviewRepository.deleteAll();
        mongoTemplate.remove(new Query(), ReviewReport.class);
        mongoTemplate.remove(new Query(), ReviewRatingStats.class);
    }

    private Review save(String bookingId, String customerId, int rating, ReviewStatus status) {
        Review review = new Review();
        review.setBookingId(bookingId);
        review.setListingId(LISTING);
        review.setProviderId(PROVIDER);
        review.setCustomerId(customerId);
        review.setRating(rating);
        review.setComment("Comment " + bookingId);
        review.setStatus(status);
        review.setCreatedAt(Instant.now().minus(Duration.ofHours(1)));
        review.setUpdatedAt(review.getCreatedAt());
        return reviewRepository.save(review);
    }

    private Review stored(String reviewId) {
        return reviewRepository.findById(reviewId).orElseThrow();
    }

    private List<ReviewReport> reports(String reviewId) {
        return mongoTemplate.find(new Query(Criteria.where("reviewId").is(reviewId)), ReviewReport.class);
    }

    private static void assertCode(Exception ex, String code) {
        ApiException api = assertInstanceOf(ApiException.class, ex);
        assertEquals(code, api.getErrorCode());
    }

    @Test
    @DisplayName("Report: counted once per user, never by the author, only for published reviews; shown in the queue")
    void reportRules() {
        Review review = save("b1", CUSTOMER, 2, ReviewStatus.PUBLISHED);
        Review held = save("b2", "cust-2", 3, ReviewStatus.AWAITING_MODERATION);
        ReportReviewRequest spam = new ReportReviewRequest(ReportReason.SPAM, "  Advert  ");

        assertEquals(ReportStatus.OPEN, moderationService.reportReview(PROVIDER, Role.PROVIDER, review.getId(), spam).status());
        assertEquals(1, stored(review.getId()).getOpenReportCount());
        assertEquals("Advert", reports(review.getId()).get(0).getDetails());

        assertCode(assertThrows(ApiException.class,
                () -> moderationService.reportReview(PROVIDER, Role.PROVIDER, review.getId(), spam)), "REVIEW_ALREADY_REPORTED");
        assertCode(assertThrows(ApiException.class,
                () -> moderationService.reportReview(CUSTOMER, Role.CUSTOMER, review.getId(), spam)), "REVIEW_REPORT_NOT_ALLOWED");
        assertCode(assertThrows(ApiException.class,
                () -> moderationService.reportReview("cust-9", Role.CUSTOMER, held.getId(), spam)), "REVIEW_NOT_PUBLISHED");

        // The rejected duplicate did not change the counter (rolled back with its transaction)
        assertEquals(1, stored(review.getId()).getOpenReportCount());
        assertEquals(1, reports(review.getId()).size());

        ModerationPageResponse reported = moderationService.getQueue(ModerationQueueFilter.REPORTED, 0, 20);
        ModerationPageResponse flagged = moderationService.getQueue(ModerationQueueFilter.FLAGGED, 0, 20);
        ModerationPageResponse all = moderationService.getQueue(ModerationQueueFilter.ALL, 0, 20);
        assertEquals(List.of(review.getId()), reported.content().stream().map(r -> r.id()).toList());
        assertEquals(List.of(held.getId()), flagged.content().stream().map(r -> r.id()).toList());
        assertEquals(2, all.totalElements());
        assertEquals(1, moderationService.getDetail(review.getId()).openReports().size());
    }

    @Test
    @DisplayName("Remove: REMOVED with the reason, history recorded, reports resolved, ratings recalculated")
    void removeReportedReview() {
        save("b1", "cust-a", 5, ReviewStatus.PUBLISHED);
        Review bad = save("b2", CUSTOMER, 1, ReviewStatus.PUBLISHED);
        ratingStats.recompute(PROVIDER, LISTING);
        assertEquals(new RatingAggregate(PROVIDER, 3.0, 2), ratingFacade.getProviderRating(PROVIDER));

        moderationService.reportReview(PROVIDER, Role.PROVIDER, bad.getId(), new ReportReviewRequest(ReportReason.OFFENSIVE, null));
        moderationService.remove(MODERATOR, bad.getId(), new RemoveReviewRequest(RemovalReason.OFFENSIVE, "Insults"));

        Review removed = stored(bad.getId());
        assertEquals(ReviewStatus.REMOVED, removed.getStatus());
        assertEquals(RemovalReason.OFFENSIVE, removed.getRemovalReason());
        assertEquals(0, removed.getOpenReportCount());
        assertEquals(ModerationAction.REMOVED, removed.getModerationHistory().get(0).getAction());
        assertEquals(MODERATOR, removed.getModerationHistory().get(0).getActorId());
        assertTrue(reports(bad.getId()).stream().allMatch(r -> r.getStatus() == ReportStatus.RESOLVED
                && r.getResolution() == ModerationAction.REMOVED && MODERATOR.equals(r.getResolvedBy())));

        assertEquals(new RatingAggregate(PROVIDER, 5.0, 1), ratingFacade.getProviderRating(PROVIDER));
        assertEquals(new RatingAggregate(LISTING, 5.0, 1), ratingFacade.getListingRating(LISTING));

        ReviewResponse customerView = reviewService.getMyReviews(CUSTOMER, 0, 10).content().get(0);
        assertEquals(RemovalReason.OFFENSIVE.getLabel(), customerView.removalReason());

        assertCode(assertThrows(ApiException.class,
                () -> moderationService.remove(MODERATOR, bad.getId(), new RemoveReviewRequest(RemovalReason.SPAM, null))),
                "REVIEW_ALREADY_REMOVED");
    }

    @Test
    @DisplayName("Publish a held review; keep a reported review (reports dismissed); nothing to decide -> 409")
    void publishAndDismiss() {
        Review held = save("b1", CUSTOMER, 4, ReviewStatus.AWAITING_MODERATION);
        Review reported = save("b2", "cust-2", 2, ReviewStatus.PUBLISHED);
        moderationService.reportReview(PROVIDER, Role.PROVIDER, reported.getId(), new ReportReviewRequest(ReportReason.FAKE, null));

        moderationService.publish(MODERATOR, held.getId(), new PublishReviewRequest("Number is the provider's office"));
        moderationService.publish(MODERATOR, reported.getId(), null);

        assertEquals(ReviewStatus.PUBLISHED, stored(held.getId()).getStatus());
        assertEquals(ModerationAction.PUBLISHED, stored(held.getId()).getModerationHistory().get(0).getAction());
        Review kept = stored(reported.getId());
        assertEquals(ReviewStatus.PUBLISHED, kept.getStatus());
        assertEquals(0, kept.getOpenReportCount());
        assertEquals(ModerationAction.REPORTS_DISMISSED, kept.getModerationHistory().get(0).getAction());
        assertEquals(ModerationAction.REPORTS_DISMISSED, reports(reported.getId()).get(0).getResolution());
        assertEquals(new RatingAggregate(PROVIDER, 3.0, 2), ratingFacade.getProviderRating(PROVIDER));

        assertCode(assertThrows(ApiException.class, () -> moderationService.publish(MODERATOR, held.getId(), null)),
                "REVIEW_NOT_IN_MODERATION");
        assertEquals(0, moderationService.getQueue(ModerationQueueFilter.ALL, 0, 20).totalElements());
    }

    @Test
    @DisplayName("Two moderators decide at the same time: exactly one decision wins, the other gets 409")
    void concurrentDecisions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Review held = save("b-" + round, CUSTOMER, 3, ReviewStatus.AWAITING_MODERATION);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> publish = pool.submit(() -> {
                    start.await();
                    return moderationService.publish("mod-a", held.getId(), null);
                });
                Future<?> remove = pool.submit(() -> {
                    start.await();
                    return moderationService.remove("mod-b", held.getId(), new RemoveReviewRequest(RemovalReason.SPAM, null));
                });
                start.countDown();

                List<Throwable> failures = new ArrayList<>();
                for (Future<?> result : List.of(publish, remove)) {
                    try {
                        result.get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException ex) {
                        failures.add(ex.getCause());
                    }
                }
                assertEquals(1, failures.size(), "exactly one decision must fail in round " + round);
                ApiException conflict = assertInstanceOf(ApiException.class, failures.get(0));
                assertEquals(409, conflict.getStatus().value());
                assertEquals(1, stored(held.getId()).getModerationHistory().size());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Customer edit adding a phone number is held for moderation and leaves the stored rating")
    void editIsCheckedAgain() {
        Review review = save("b1", CUSTOMER, 5, ReviewStatus.PUBLISHED);
        ratingStats.recompute(PROVIDER, LISTING);
        assertEquals(1, ratingFacade.getProviderRating(PROVIDER).ratingCount());

        ReviewResponse edited = reviewService.updateReview(CUSTOMER, review.getId(),
                new UpdateReviewRequest(5, "Great! Call me on 0771234567 next time"));

        assertEquals(ReviewStatus.AWAITING_MODERATION, edited.status());
        Review held = stored(review.getId());
        assertEquals(List.of(ModerationFlag.CONTACT_DETAILS), held.getModerationFlags());
        assertEquals(ModerationAction.AUTO_FLAGGED, held.getModerationHistory().get(0).getAction());
        assertEquals(new RatingAggregate(PROVIDER, 0.0, 0), ratingFacade.getProviderRating(PROVIDER));
        assertEquals(1, moderationService.getQueue(ModerationQueueFilter.FLAGGED, 0, 20).totalElements());
    }

    @Test
    @DisplayName("Facade: first read computes and stores the rating; a listing without reviews is 0.0 / 0")
    void facadeComputesOnFirstRead() {
        save("b1", "cust-a", 4, ReviewStatus.PUBLISHED);
        save("b2", "cust-b", 5, ReviewStatus.PUBLISHED);
        save("b3", "cust-c", 1, ReviewStatus.REMOVED);

        assertEquals(new RatingAggregate(PROVIDER, 4.5, 2), ratingFacade.getProviderRating(PROVIDER));
        ReviewRatingStats storedStats = mongoTemplate.findById("PROVIDER:" + PROVIDER, ReviewRatingStats.class);
        assertEquals(2L, storedStats.getRatingCounts().get("5") + storedStats.getRatingCounts().get("4"));
        assertEquals(new RatingAggregate("listing-none", 0.0, 0), ratingFacade.getListingRating("listing-none"));
        assertNull(mongoTemplate.findById("PROVIDER:prov-unknown", ReviewRatingStats.class));
    }
}
