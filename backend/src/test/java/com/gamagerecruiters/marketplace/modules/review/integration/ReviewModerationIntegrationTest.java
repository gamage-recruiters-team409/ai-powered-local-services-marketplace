package com.gamagerecruiters.marketplace.modules.review.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingQuery;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingStatsService;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

/**
 * Real MongoDB (replica set, transactions) checks for review moderation (FR-REV-003) and the stored
 * rating aggregates (FR-REV-004): reports, the queue, moderator decisions, report resolution,
 * concurrent decisions (moderation revision), provider visibility of held/removed reviews, re-checking edits,
 * rating recalculation, and recovery of an aggregate after a failed recalculation.
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

    /** The real rating query; recovery tests make it fail to simulate a database outage during a recompute. */
    @MockitoSpyBean
    private ReviewRatingQuery ratingQuery;

    @AfterEach
    void restoreRatingQuery() {
        reset(ratingQuery);
    }

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

    private long rev(String reviewId) {
        return stored(reviewId).getModerationRevision();
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
        moderationService.remove(MODERATOR, bad.getId(), new RemoveReviewRequest(RemovalReason.OFFENSIVE, "Insults", rev(bad.getId())));

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
                () -> moderationService.remove(MODERATOR, bad.getId(), new RemoveReviewRequest(RemovalReason.SPAM, null, rev(bad.getId())))),
                "REVIEW_ALREADY_REMOVED");
    }

    @Test
    @DisplayName("Publish a held review; keep a reported review (reports dismissed); nothing to decide -> 409")
    void publishAndDismiss() {
        Review held = save("b1", CUSTOMER, 4, ReviewStatus.AWAITING_MODERATION);
        Review reported = save("b2", "cust-2", 2, ReviewStatus.PUBLISHED);
        moderationService.reportReview(PROVIDER, Role.PROVIDER, reported.getId(), new ReportReviewRequest(ReportReason.FAKE, null));

        moderationService.publish(MODERATOR, held.getId(), new PublishReviewRequest("Number is the provider's office", rev(held.getId())));
        moderationService.publish(MODERATOR, reported.getId(), new PublishReviewRequest(null, rev(reported.getId())));

        assertEquals(ReviewStatus.PUBLISHED, stored(held.getId()).getStatus());
        assertEquals(ModerationAction.PUBLISHED, stored(held.getId()).getModerationHistory().get(0).getAction());
        Review kept = stored(reported.getId());
        assertEquals(ReviewStatus.PUBLISHED, kept.getStatus());
        assertEquals(0, kept.getOpenReportCount());
        assertEquals(ModerationAction.REPORTS_DISMISSED, kept.getModerationHistory().get(0).getAction());
        assertEquals(ModerationAction.REPORTS_DISMISSED, reports(reported.getId()).get(0).getResolution());
        assertEquals(new RatingAggregate(PROVIDER, 3.0, 2), ratingFacade.getProviderRating(PROVIDER));

        assertCode(assertThrows(ApiException.class,
                () -> moderationService.publish(MODERATOR, held.getId(), new PublishReviewRequest(null, rev(held.getId())))),
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
                    return moderationService.publish("mod-a", held.getId(), new PublishReviewRequest(null, 0L));
                });
                Future<?> remove = pool.submit(() -> {
                    start.await();
                    return moderationService.remove("mod-b", held.getId(), new RemoveReviewRequest(RemovalReason.SPAM, null, 0L));
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

    // ------------------------------------------------------------------ helpers for the PR #41 review fixes

    private void cleanAll() {
        reviewRepository.deleteAll();
        mongoTemplate.remove(new Query(), ReviewReport.class);
        mongoTemplate.remove(new Query(), ReviewRatingStats.class);
    }

    /** The stored aggregate (not the facade, which would repair it) matches the PUBLISHED reviews. */
    private void assertStoredRatingsMatchReviews() {
        long published = reviewRepository.countByProviderIdAndStatus(PROVIDER, ReviewStatus.PUBLISHED);
        ReviewRatingStats provider = mongoTemplate.findById("PROVIDER:" + PROVIDER, ReviewRatingStats.class);
        ReviewRatingStats listing = mongoTemplate.findById("LISTING:" + LISTING, ReviewRatingStats.class);
        assertEquals(published, provider.getRatingCount());
        assertEquals(published, listing.getRatingCount());
    }

    private void failRatingRecompute() {
        doThrow(new DataAccessResourceFailureException("Simulated MongoDB outage"))
                .when(ratingQuery).countPublishedByRating(PROVIDER);
        doThrow(new DataAccessResourceFailureException("Simulated MongoDB outage"))
                .when(ratingQuery).countPublishedByRatingForListing(LISTING);
    }

    private ReviewRatingStats storedStats(String id) {
        return mongoTemplate.findById(id, ReviewRatingStats.class);
    }

    private List<Throwable> runTogether(java.util.concurrent.Callable<?> first, java.util.concurrent.Callable<?> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<?> work : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> result : futures) {
                try {
                    result.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertConflict(Throwable failure) {
        ApiException conflict = assertInstanceOf(ApiException.class, failure, () -> "Unexpected failure: " + failure);
        assertEquals(409, conflict.getStatus().value());
    }

    // ------------------------------------------------------------------ Finding 1: provider visibility

    @Test
    @DisplayName("Provider list: published visible; held and removed hidden; a held review appears after it is published")
    void providerSeesOnlyApprovedReviews() {
        Review published = save("b1", "cust-a", 5, ReviewStatus.PUBLISHED);
        Review held = save("b2", CUSTOMER, 4, ReviewStatus.AWAITING_MODERATION);
        held.setComment("Call me on 0771234567");
        reviewRepository.save(held);
        Review removed = save("b3", "cust-c", 1, ReviewStatus.REMOVED);

        List<ReviewResponse> before = reviewService.getProviderOwnReviews(PROVIDER, 0, 10).content();
        assertEquals(List.of(published.getId()), before.stream().map(ReviewResponse::id).toList());
        assertTrue(before.stream().noneMatch(r -> r.comment() != null && r.comment().contains("0771234567")));
        assertTrue(before.stream().noneMatch(r -> removed.getId().equals(r.id())));

        // The author still sees the held review with its text (S09), the provider does not
        assertEquals("Call me on 0771234567", reviewService.getMyReviews(CUSTOMER, 0, 10).content().get(0).comment());

        moderationService.publish(MODERATOR, held.getId(), new PublishReviewRequest("Office number", rev(held.getId())));
        List<String> afterPublish = reviewService.getProviderOwnReviews(PROVIDER, 0, 10).content().stream()
                .map(ReviewResponse::id).toList();
        assertEquals(2, afterPublish.size());
        assertTrue(afterPublish.contains(held.getId()));

        moderationService.remove(MODERATOR, published.getId(),
                new RemoveReviewRequest(RemovalReason.SPAM, null, rev(published.getId())));
        List<String> afterRemove = reviewService.getProviderOwnReviews(PROVIDER, 0, 10).content().stream()
                .map(ReviewResponse::id).toList();
        assertEquals(List.of(held.getId()), afterRemove);
        assertEquals(1, reviewService.getProviderOwnReviews(PROVIDER, 0, 10).totalElements());
    }

    // ------------------------------------------------------------------ Finding 4: moderation revision

    @Test
    @DisplayName("Stale decision: B decided on revision 1 after A dismissed the reports -> 409; after reloading B can remove")
    void staleRemoveAfterDismissIsRejected() {
        Review review = save("b1", CUSTOMER, 2, ReviewStatus.PUBLISHED);
        moderationService.reportReview(PROVIDER, Role.PROVIDER, review.getId(), new ReportReviewRequest(ReportReason.FAKE, null));
        long seenByBoth = rev(review.getId());
        assertEquals(1, seenByBoth);

        moderationService.publish("mod-a", review.getId(), new PublishReviewRequest(null, seenByBoth));
        assertCode(assertThrows(ApiException.class, () -> moderationService.remove("mod-b", review.getId(),
                new RemoveReviewRequest(RemovalReason.FAKE, null, seenByBoth))), "REVIEW_UPDATE_CONFLICT");

        Review afterConflict = stored(review.getId());
        assertEquals(ReviewStatus.PUBLISHED, afterConflict.getStatus());
        assertEquals(1, afterConflict.getModerationHistory().size());
        assertEquals(2, afterConflict.getModerationRevision());

        // A fresh, authorized decision on the reloaded review is still possible
        moderationService.remove("mod-b", review.getId(), new RemoveReviewRequest(RemovalReason.FAKE, "Checked again", 2L));
        Review removed = stored(review.getId());
        assertEquals(ReviewStatus.REMOVED, removed.getStatus());
        assertEquals(List.of(ModerationAction.REPORTS_DISMISSED, ModerationAction.REMOVED),
                removed.getModerationHistory().stream().map(e -> e.getAction()).toList());
        assertStoredRatingsMatchReviews();
    }

    @Test
    @DisplayName("A report that arrives after the moderator opened the review makes the dismissal stale (409, report stays open)")
    void newReportMakesDecisionStale() {
        Review review = save("b1", CUSTOMER, 2, ReviewStatus.PUBLISHED);
        moderationService.reportReview(PROVIDER, Role.PROVIDER, review.getId(), new ReportReviewRequest(ReportReason.FAKE, null));
        long seen = rev(review.getId());
        moderationService.reportReview("cust-9", Role.CUSTOMER, review.getId(), new ReportReviewRequest(ReportReason.SPAM, null));

        assertCode(assertThrows(ApiException.class,
                () -> moderationService.publish(MODERATOR, review.getId(), new PublishReviewRequest(null, seen))),
                "REVIEW_UPDATE_CONFLICT");
        assertTrue(reports(review.getId()).stream().allMatch(r -> r.getStatus() == ReportStatus.OPEN));
        assertEquals(2, stored(review.getId()).getOpenReportCount());
    }

    @Test
    @DisplayName("A decision without the revision is rejected with 400 MODERATION_REVISION_REQUIRED")
    void revisionIsRequired() {
        Review held = save("b1", CUSTOMER, 4, ReviewStatus.AWAITING_MODERATION);
        assertCode(assertThrows(ApiException.class, () -> moderationService.publish(MODERATOR, held.getId(), null)),
                "MODERATION_REVISION_REQUIRED");
        assertCode(assertThrows(ApiException.class, () -> moderationService.remove(MODERATOR, held.getId(),
                new RemoveReviewRequest(RemovalReason.SPAM, null, null))), "MODERATION_REVISION_REQUIRED");
        assertEquals(ReviewStatus.AWAITING_MODERATION, stored(held.getId()).getStatus());
    }

    @Test
    @DisplayName("A review stored before the revision field existed is treated as revision 0")
    void legacyReviewWithoutRevision() {
        Review held = save("b1", CUSTOMER, 4, ReviewStatus.AWAITING_MODERATION);
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(held.getId())),
                new Update().unset("moderationRevision"), Review.class);

        moderationService.publish(MODERATOR, held.getId(), new PublishReviewRequest(null, 0L));

        assertEquals(ReviewStatus.PUBLISHED, stored(held.getId()).getStatus());
        assertEquals(1, stored(held.getId()).getModerationRevision());
    }

    @Test
    @DisplayName("Concurrent: dismiss reports vs remove on the same revision -> exactly one wins; history, reports, ratings consistent")
    void concurrentDismissVersusRemove() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            cleanAll();
            save("b-other", "cust-a", 5, ReviewStatus.PUBLISHED);
            Review review = save("b-" + round, CUSTOMER, 1, ReviewStatus.PUBLISHED);
            moderationService.reportReview(PROVIDER, Role.PROVIDER, review.getId(),
                    new ReportReviewRequest(ReportReason.OFFENSIVE, null));
            ratingStats.recompute(PROVIDER, LISTING);
            long seen = rev(review.getId());

            List<Throwable> failures = runTogether(
                    () -> moderationService.publish("mod-a", review.getId(), new PublishReviewRequest(null, seen)),
                    () -> moderationService.remove("mod-b", review.getId(), new RemoveReviewRequest(RemovalReason.OFFENSIVE, null, seen)));

            assertEquals(1, failures.size(), "exactly one decision must fail in round " + round);
            assertConflict(failures.get(0));
            Review decided = stored(review.getId());
            assertEquals(1, decided.getModerationHistory().size());
            assertEquals(seen + 1, decided.getModerationRevision());
            ModerationAction winner = decided.getModerationHistory().get(0).getAction();
            assertEquals(winner == ModerationAction.REMOVED ? ReviewStatus.REMOVED : ReviewStatus.PUBLISHED, decided.getStatus());
            assertTrue(reports(review.getId()).stream().allMatch(r -> r.getStatus() == ReportStatus.RESOLVED
                    && r.getResolution() == winner));
            assertStoredRatingsMatchReviews();
        }
    }

    @Test
    @DisplayName("Concurrent: two moderators dismiss the same report -> exactly one dismissal is recorded")
    void concurrentDoubleDismiss() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            cleanAll();
            Review review = save("b-" + round, CUSTOMER, 3, ReviewStatus.PUBLISHED);
            moderationService.reportReview(PROVIDER, Role.PROVIDER, review.getId(), new ReportReviewRequest(ReportReason.SPAM, null));
            long seen = rev(review.getId());

            List<Throwable> failures = runTogether(
                    () -> moderationService.publish("mod-a", review.getId(), new PublishReviewRequest(null, seen)),
                    () -> moderationService.publish("mod-b", review.getId(), new PublishReviewRequest(null, seen)));

            assertEquals(1, failures.size(), "exactly one dismissal must fail in round " + round);
            assertConflict(failures.get(0));
            Review decided = stored(review.getId());
            assertEquals(1, decided.getModerationHistory().size());
            assertEquals(ModerationAction.REPORTS_DISMISSED, decided.getModerationHistory().get(0).getAction());
            assertEquals(0, decided.getOpenReportCount());
            assertEquals(1, reports(review.getId()).size());
            assertEquals(decided.getModerationHistory().get(0).getActorId(), reports(review.getId()).get(0).getResolvedBy());
        }
    }

    // ------------------------------------------------------------------ Finding 3: rating recovery

    @Test
    @DisplayName("Publish with a failed recompute: review stays published; facade reads repair provider and listing ratings")
    void publishRecoversAfterFailedRecompute() {
        save("b1", "cust-a", 5, ReviewStatus.PUBLISHED);
        Review held = save("b2", CUSTOMER, 3, ReviewStatus.AWAITING_MODERATION);
        ratingStats.recompute(PROVIDER, LISTING);
        assertEquals(new RatingAggregate(PROVIDER, 5.0, 1), ratingFacade.getProviderRating(PROVIDER));

        failRatingRecompute();
        moderationService.publish(MODERATOR, held.getId(), new PublishReviewRequest(null, rev(held.getId())));

        assertEquals(ReviewStatus.PUBLISHED, stored(held.getId()).getStatus());
        assertEquals(1, storedStats("PROVIDER:" + PROVIDER).getRatingCount()); // stale: recompute failed
        assertEquals(1, storedStats("LISTING:" + LISTING).getRatingCount());
        // While the database still fails, reads return the last stored values instead of an error
        assertEquals(new RatingAggregate(PROVIDER, 5.0, 1), ratingFacade.getProviderRating(PROVIDER));

        reset(ratingQuery);
        assertEquals(new RatingAggregate(PROVIDER, 4.0, 2), ratingFacade.getProviderRating(PROVIDER));
        assertEquals(new RatingAggregate(LISTING, 4.0, 2), ratingFacade.getListingRating(LISTING));
        assertStoredRatingsMatchReviews();
    }

    @Test
    @DisplayName("Edit with a failed recompute: the edit is saved; facade reads repair provider and listing ratings")
    void editRecoversAfterFailedRecompute() {
        Review review = save("b1", CUSTOMER, 5, ReviewStatus.PUBLISHED);
        save("b2", "cust-b", 5, ReviewStatus.PUBLISHED);
        ratingStats.recompute(PROVIDER, LISTING);

        failRatingRecompute();
        ReviewResponse edited = reviewService.updateReview(CUSTOMER, review.getId(),
                new UpdateReviewRequest(1, "Not as good as it looked at first"));

        assertEquals(1, edited.rating());
        assertEquals(1, stored(review.getId()).getRating());
        assertEquals(5.0, storedStats("PROVIDER:" + PROVIDER).getRatingAvg()); // stale

        reset(ratingQuery);
        assertEquals(new RatingAggregate(PROVIDER, 3.0, 2), ratingFacade.getProviderRating(PROVIDER));
        assertEquals(new RatingAggregate(LISTING, 3.0, 2), ratingFacade.getListingRating(LISTING));
        assertEquals(3.0, storedStats("LISTING:" + LISTING).getRatingAvg());
    }

    @Test
    @DisplayName("Remove with a failed recompute: the removal is saved; facade reads repair provider and listing ratings")
    void removeRecoversAfterFailedRecompute() {
        save("b1", "cust-a", 5, ReviewStatus.PUBLISHED);
        Review bad = save("b2", CUSTOMER, 1, ReviewStatus.PUBLISHED);
        ratingStats.recompute(PROVIDER, LISTING);
        assertEquals(2, storedStats("PROVIDER:" + PROVIDER).getRatingCount());

        failRatingRecompute();
        moderationService.remove(MODERATOR, bad.getId(), new RemoveReviewRequest(RemovalReason.SPAM, null, rev(bad.getId())));

        assertEquals(ReviewStatus.REMOVED, stored(bad.getId()).getStatus());
        assertEquals(2, storedStats("PROVIDER:" + PROVIDER).getRatingCount()); // stale

        reset(ratingQuery);
        assertEquals(new RatingAggregate(PROVIDER, 5.0, 1), ratingFacade.getProviderRating(PROVIDER));
        assertEquals(new RatingAggregate(LISTING, 5.0, 1), ratingFacade.getListingRating(LISTING));
        assertStoredRatingsMatchReviews();
    }

    @Test
    @DisplayName("An up-to-date aggregate is not recomputed on read once the last change is older than the margin")
    void freshAggregateIsReadAsStored() {
        save("b1", "cust-a", 4, ReviewStatus.PUBLISHED); // changed 1 hour ago
        ratingStats.recompute(PROVIDER, LISTING);

        clearInvocations(ratingQuery);
        assertEquals(new RatingAggregate(PROVIDER, 4.0, 1), ratingFacade.getProviderRating(PROVIDER));
        assertEquals(new RatingAggregate(LISTING, 4.0, 1), ratingFacade.getListingRating(LISTING));
        verify(ratingQuery, never()).countPublishedByRating(PROVIDER);
        verify(ratingQuery, never()).countPublishedByRatingForListing(LISTING);
    }
}
