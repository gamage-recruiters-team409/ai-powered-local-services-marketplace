package com.gamagerecruiters.marketplace.modules.review.moderation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationAction;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationEntry;
import com.gamagerecruiters.marketplace.modules.review.domain.RemovalReason;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportStatus;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewReport;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationReviewDetailResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.PublishReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.RemoveReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ReportReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewReportResponse;
import com.gamagerecruiters.marketplace.modules.review.mapper.ReviewMapper;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewAtomicUpdates;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewPeople;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewRatingStatsService;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewTransactions;

/**
 * Review reports and moderation decisions (FR-REV-003, S10 and S14–S16).
 *
 * <ul>
 *   <li>Report: a signed-in user (not the review's author) reports a PUBLISHED review once. The report and the
 *       review's open-report counter are written in one transaction. The review stays visible (a report alone
 *       does not change the status); it appears in the moderation queue.</li>
 *   <li>Queue: reviews held by the automated checks (AWAITING_MODERATION) and published reviews with open
 *       reports, oldest first.</li>
 *   <li>Decision: publish a held review, keep a reported review (reports dismissed), or remove a review with a
 *       reason. The moderator sends the review's {@code moderationRevision} they saw; each decision is one
 *       conditional update on that revision (two decisions based on the same state cannot both succeed, also when
 *       the status does not change, e.g. dismissing reports), is recorded in the
 *       review's moderation history (BR-014), closes the open reports in the same transaction, and recalculates
 *       the stored ratings (FR-REV-004). Providers can never delete reviews (BR-012).</li>
 * </ul>
 */
@Service
public class ReviewModerationService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(ReviewModerationService.class);

    private final ReviewRepository reviewRepository;
    private final MongoTemplate mongoTemplate;
    private final ReviewAtomicUpdates reviewUpdates;
    private final ReviewTransactions transactions;
    private final ReviewRatingStatsService ratingStats;
    private final ReviewPeople people;
    private final Clock clock;

    @Autowired
    public ReviewModerationService(ReviewRepository reviewRepository, MongoTemplate mongoTemplate,
                                   ReviewAtomicUpdates reviewUpdates, ReviewTransactions transactions,
                                   ReviewRatingStatsService ratingStats, ReviewPeople people) {
        this(reviewRepository, mongoTemplate, reviewUpdates, transactions, ratingStats, people, Clock.systemUTC());
    }

    ReviewModerationService(ReviewRepository reviewRepository, MongoTemplate mongoTemplate,
                            ReviewAtomicUpdates reviewUpdates, ReviewTransactions transactions,
                            ReviewRatingStatsService ratingStats, ReviewPeople people, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.mongoTemplate = mongoTemplate;
        this.reviewUpdates = reviewUpdates;
        this.transactions = transactions;
        this.ratingStats = ratingStats;
        this.people = people;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ S10 report

    public ReviewReportResponse reportReview(String reporterId, Role reporterRole, String reviewId,
                                             ReportReviewRequest request) {
        Review review = findReview(reviewId);
        if (reporterId != null && reporterId.equals(review.getCustomerId())) {
            throw new ResourceConflictException("REVIEW_REPORT_NOT_ALLOWED", "You cannot report your own review");
        }
        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw notPublished();
        }

        Instant now = clock.instant();
        ReviewReport report = new ReviewReport();
        report.setReviewId(reviewId);
        report.setReporterId(reporterId);
        report.setReporterRole(reporterRole);
        report.setReason(request.reason());
        report.setDetails(trimToNull(request.details()));
        report.setStatus(ReportStatus.OPEN);
        report.setCreatedAt(now);

        ReviewReport saved = transactions.inTransaction(() -> {
            ReviewReport inserted;
            try {
                inserted = mongoTemplate.insert(report);
            } catch (DuplicateKeyException ex) {
                // Unique index reviewId + reporterId: one report per user per review
                throw new ResourceConflictException("REVIEW_ALREADY_REPORTED", "You have already reported this review");
            }
            // Counted only while the review is still published; otherwise the whole report is rolled back
            reviewUpdates.addOpenReport(reviewId, now).orElseThrow(ReviewModerationService::notPublished);
            return inserted;
        });
        log.info("event=REVIEW_REPORTED reviewId={} reportId={} reason={} reporterRole={}",
                reviewId, saved.getId(), saved.getReason(), reporterRole);
        return ReviewMapper.toReportResponse(saved);
    }

    // ------------------------------------------------------------------ S14 queue, S15 detail

    public ModerationPageResponse getQueue(ModerationQueueFilter filter, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Query query = new Query(queueCriteria(filter == null ? ModerationQueueFilter.ALL : filter));
        long total = mongoTemplate.count(query, Review.class);
        query.with(Sort.by(Sort.Direction.ASC, "createdAt")).skip((long) safePage * safeSize).limit(safeSize);
        List<Review> reviews = mongoTemplate.find(query, Review.class);

        List<ModerationReviewResponse> content = toModerationResponses(reviews);
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new ModerationPageResponse(content, safePage, safeSize, total, totalPages);
    }

    public ModerationReviewDetailResponse getDetail(String reviewId) {
        Review review = findReview(reviewId);
        List<ReviewReportResponse> openReports = openReports(reviewId).stream()
                .map(ReviewMapper::toReportResponse)
                .toList();
        List<ModerationEntry> history = review.getModerationHistory() == null ? List.of() : review.getModerationHistory();
        return new ModerationReviewDetailResponse(
                toModerationResponses(List.of(review)).get(0),
                openReports,
                history.stream().map(ReviewMapper::toEntryResponse).toList());
    }

    // ------------------------------------------------------------------ S16 decisions

    /** Publishes a held review, or keeps a reported published review and closes its reports. */
    public ModerationReviewResponse publish(String moderatorId, String reviewId, PublishReviewRequest request) {
        long revision = requireRevision(request == null ? null : request.revision());
        Review review = findReview(reviewId);
        ReviewStatus status = review.getStatus();
        ModerationAction action;
        boolean requireReports;
        if (status == ReviewStatus.AWAITING_MODERATION) {
            action = ModerationAction.PUBLISHED;
            requireReports = false;
        } else if (status == ReviewStatus.PUBLISHED && review.getOpenReportCount() > 0) {
            action = ModerationAction.REPORTS_DISMISSED;
            requireReports = true;
        } else if (status == ReviewStatus.REMOVED) {
            throw alreadyRemoved();
        } else {
            throw new ResourceConflictException("REVIEW_NOT_IN_MODERATION",
                    "This review is not waiting for a moderation decision");
        }

        Instant now = clock.instant();
        ModerationEntry entry = ModerationEntry.of(action, status, ReviewStatus.PUBLISHED, moderatorId, now);
        entry.setNote(trimToNull(request == null ? null : request.note()));

        Review decided = decide(review, revision, requireReports, ReviewStatus.PUBLISHED, null, entry, action,
                moderatorId, now);
        return toModerationResponses(List.of(decided)).get(0);
    }

    /** Removes a held or published review with a reason that is shown to the customer. */
    public ModerationReviewResponse remove(String moderatorId, String reviewId, RemoveReviewRequest request) {
        long revision = requireRevision(request.revision());
        Review review = findReview(reviewId);
        ReviewStatus status = review.getStatus();
        if (status == ReviewStatus.REMOVED) {
            throw alreadyRemoved();
        }

        Instant now = clock.instant();
        ModerationEntry entry = ModerationEntry.of(ModerationAction.REMOVED, status, ReviewStatus.REMOVED, moderatorId, now);
        entry.setReasonCode(request.reasonCode());
        entry.setNote(trimToNull(request.note()));

        Review decided = decide(review, revision, false, ReviewStatus.REMOVED, request.reasonCode(), entry,
                ModerationAction.REMOVED, moderatorId, now);
        return toModerationResponses(List.of(decided)).get(0);
    }

    private Review decide(Review review, long revision, boolean requireReports, ReviewStatus newStatus,
                          RemovalReason removalReason,
                          ModerationEntry entry, ModerationAction resolution, String moderatorId, Instant now) {
        String reviewId = review.getId();
        ReviewStatus expectedStatus = review.getStatus();
        if (review.getModerationRevision() != revision) {
            // Already outdated before trying: the moderator decided on a state that has changed since
            throw staleRevision();
        }
        Review decided = transactions.inTransaction(() -> {
            // Same check inside the conditional update, so a decision committed meanwhile also makes this one fail
            Review updated = reviewUpdates.applyModerationDecision(reviewId, revision, expectedStatus, requireReports,
                            newStatus, removalReason, entry, now)
                    .orElseThrow(ReviewModerationService::staleRevision);
            resolveOpenReports(reviewId, resolution, moderatorId, now);
            return updated;
        });
        log.info("event=REVIEW_MODERATED reviewId={} action={} from={} to={} moderatorId={}",
                reviewId, resolution, expectedStatus, newStatus, moderatorId);
        ratingStats.recompute(decided.getProviderId(), decided.getListingId());
        return decided;
    }

    // ------------------------------------------------------------------ helpers

    static Criteria queueCriteria(ModerationQueueFilter filter) {
        Criteria flagged = Criteria.where("status").is(ReviewStatus.AWAITING_MODERATION.name());
        Criteria reported = Criteria.where("status").is(ReviewStatus.PUBLISHED.name()).and("openReportCount").gt(0);
        return switch (filter) {
            case FLAGGED -> flagged;
            case REPORTED -> reported;
            case ALL -> new Criteria().orOperator(flagged, reported);
        };
    }

    private List<ReviewReport> openReports(String reviewId) {
        Query query = new Query(Criteria.where("reviewId").is(reviewId).and("status").is(ReportStatus.OPEN.name()))
                .with(Sort.by(Sort.Direction.ASC, "createdAt"));
        return mongoTemplate.find(query, ReviewReport.class);
    }

    private void resolveOpenReports(String reviewId, ModerationAction resolution, String moderatorId, Instant now) {
        Query query = new Query(Criteria.where("reviewId").is(reviewId).and("status").is(ReportStatus.OPEN.name()));
        Update update = new Update()
                .set("status", ReportStatus.RESOLVED)
                .set("resolution", resolution)
                .set("resolvedBy", moderatorId)
                .set("resolvedAt", now);
        mongoTemplate.updateMulti(query, update, ReviewReport.class);
    }

    private List<ModerationReviewResponse> toModerationResponses(List<Review> reviews) {
        List<String> ids = new ArrayList<>();
        reviews.forEach(r -> {
            ids.add(r.getCustomerId());
            ids.add(r.getProviderId());
        });
        Map<String, String> names = people.namesOf(ids);
        return reviews.stream()
                .map(r -> ReviewMapper.toModerationResponse(r, names.get(r.getProviderId()), names.get(r.getCustomerId())))
                .toList();
    }

    private Review findReview(String reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("REVIEW_NOT_FOUND", "Review not found"));
    }

    private static long requireRevision(Long revision) {
        if (revision == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODERATION_REVISION_REQUIRED",
                    "Send the review's moderationRevision with the decision");
        }
        return revision;
    }

    private static ResourceConflictException staleRevision() {
        return new ResourceConflictException("REVIEW_UPDATE_CONFLICT",
                "This review was changed by another request at the same time. Please reload it and try again");
    }

    private static ResourceConflictException notPublished() {
        return new ResourceConflictException("REVIEW_NOT_PUBLISHED", "Only published reviews can be reported");
    }

    private static ResourceConflictException alreadyRemoved() {
        return new ResourceConflictException("REVIEW_ALREADY_REMOVED", "This review has already been removed");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
