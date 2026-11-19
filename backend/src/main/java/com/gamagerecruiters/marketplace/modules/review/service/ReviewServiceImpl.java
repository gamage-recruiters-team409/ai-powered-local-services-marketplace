package com.gamagerecruiters.marketplace.modules.review.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationAction;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationEntry;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;
import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.CreateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.UpdateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.mapper.ReviewMapper;
import com.gamagerecruiters.marketplace.modules.review.moderation.ReviewContentChecker;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;

/**
 * Review rules (agreed for WP08):
 * - only the booking's customer can review, only when the booking is COMPLETED;
 * - within 7 days of completion; one review per booking (unique bookingId index);
 *   if the completion time cannot be verified, the review is rejected (never unlimited);
 * - automated checks run before publishing (FR-REV-003): a clean review is PUBLISHED straight away,
 *   a flagged one (contact details, links, blocked words) is held as AWAITING_MODERATION for a moderator;
 *   an edit is checked again; users can report published reviews (ReviewModerationService);
 * - the stored provider/listing rating aggregates are recalculated after every change (FR-REV-004);
 * - the customer can edit within 48 hours, and not after the provider has replied;
 *   a review without a posting time cannot be edited;
 * - edits and replies are atomic conditional updates (ReviewAtomicUpdates), so concurrent
 *   requests cannot overwrite each other; a lost race gets 409 instead of a silent overwrite;
 * - the provider has one reply per published review and can edit it;
 * - public lists, the provider's own list (S11) and the rating summary use PUBLISHED reviews only, newest first;
 *   held (AWAITING_MODERATION) and REMOVED reviews are visible only to their author and to moderators.
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    static final Duration REVIEW_WINDOW = Duration.ofDays(7);
    static final Duration EDIT_WINDOW = Duration.ofHours(48);
    static final int MAX_PAGE_SIZE = 50;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ReviewRepository reviewRepository;
    private final BookingFacade bookingFacade;
    private final CompletionLookup completionLookup;
    private final ReviewRatingQuery ratingQuery;
    private final ReviewAtomicUpdates reviewUpdates;
    private final ReviewContentChecker contentChecker;
    private final ReviewRatingStatsService ratingStats;
    private final ReviewPeople people;
    private final Clock clock;

    @Autowired
    public ReviewServiceImpl(ReviewRepository reviewRepository, BookingFacade bookingFacade,
                             CompletionLookup completionLookup, ReviewRatingQuery ratingQuery,
                             ReviewAtomicUpdates reviewUpdates, ReviewContentChecker contentChecker,
                             ReviewRatingStatsService ratingStats, ReviewPeople people) {
        this(reviewRepository, bookingFacade, completionLookup, ratingQuery, reviewUpdates, contentChecker,
                ratingStats, people, Clock.systemUTC());
    }

    ReviewServiceImpl(ReviewRepository reviewRepository, BookingFacade bookingFacade,
                      CompletionLookup completionLookup, ReviewRatingQuery ratingQuery,
                      ReviewAtomicUpdates reviewUpdates, ReviewContentChecker contentChecker,
                      ReviewRatingStatsService ratingStats, ReviewPeople people, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.bookingFacade = bookingFacade;
        this.completionLookup = completionLookup;
        this.ratingQuery = ratingQuery;
        this.reviewUpdates = reviewUpdates;
        this.contentChecker = contentChecker;
        this.ratingStats = ratingStats;
        this.people = people;
        this.clock = clock;
    }

    @Override
    public ReviewResponse createReview(String customerId, CreateReviewRequest request) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(request.bookingId());
        OwnershipChecker.assertOwner(customerId, booking.customerId());

        if (booking.status() != BookingStatus.COMPLETED) {
            throw new ResourceConflictException("BOOKING_NOT_COMPLETED",
                    "You can review a booking only after it has been completed");
        }

        Instant now = clock.instant();
        // No trustworthy completion time = no review (a missing timestamp must not remove the 7-day limit)
        Instant completedAt = completionLookup.findConfirmedAt(booking.bookingId())
                .orElseThrow(() -> new ResourceConflictException("REVIEW_WINDOW_UNAVAILABLE",
                        "The completion time of this booking could not be verified, so a review cannot be submitted"));
        if (now.isAfter(completedAt.plus(REVIEW_WINDOW))) {
            throw new ResourceConflictException("REVIEW_WINDOW_CLOSED",
                    "Reviews can be submitted within 7 days of completion");
        }

        if (reviewRepository.existsByBookingId(booking.bookingId())) {
            throw reviewAlreadyExists();
        }

        Review review = new Review();
        review.setBookingId(booking.bookingId());
        review.setListingId(booking.listingId());
        review.setProviderId(booking.providerId());
        review.setCustomerId(booking.customerId());
        review.setRating(request.rating());
        review.setComment(trimToNull(request.comment()));
        review.setEdited(false);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);

        // Automated checks before publishing (FR-REV-003)
        List<ModerationFlag> flags = contentChecker.check(review.getComment());
        review.setModerationFlags(flags);
        if (flags.isEmpty()) {
            review.setStatus(ReviewStatus.PUBLISHED);
        } else {
            review.setStatus(ReviewStatus.AWAITING_MODERATION);
            review.getModerationHistory().add(autoFlagEntry(null, flags, now));
        }

        Review saved;
        try {
            saved = reviewRepository.save(review);
        } catch (DuplicateKeyException ex) {
            // Unique bookingId index: a concurrent request already created the review
            throw reviewAlreadyExists();
        }
        ratingStats.recompute(saved.getProviderId(), saved.getListingId());
        return ReviewMapper.toResponse(saved);
    }

    @Override
    public ReviewResponse updateReview(String customerId, String reviewId, UpdateReviewRequest request) {
        Review review = findReview(reviewId);
        OwnershipChecker.assertOwner(customerId, review.getCustomerId());

        if (review.getStatus() == ReviewStatus.REMOVED) {
            throw new ResourceConflictException("REVIEW_NOT_EDITABLE", "This review has been removed and cannot be edited");
        }
        if (review.getProviderReply() != null) {
            throw new ResourceConflictException("REVIEW_NOT_EDITABLE",
                    "This review cannot be edited because the provider has already replied");
        }
        Instant now = clock.instant();
        // No posting time = no edit (a missing timestamp must not remove the 48-hour limit)
        if (review.getCreatedAt() == null) {
            throw new ResourceConflictException("REVIEW_EDIT_WINDOW_UNAVAILABLE",
                    "The posting time of this review could not be verified, so it cannot be edited");
        }
        if (now.isAfter(review.getCreatedAt().plus(EDIT_WINDOW))) {
            throw new ResourceConflictException("REVIEW_EDIT_WINDOW_CLOSED",
                    "Reviews can be edited within 48 hours of posting");
        }

        // The edited text is checked again (FR-REV-003): clean -> PUBLISHED, flagged -> AWAITING_MODERATION
        String comment = trimToNull(request.comment());
        List<ModerationFlag> flags = contentChecker.check(comment);
        ReviewStatus newStatus = flags.isEmpty() ? ReviewStatus.PUBLISHED : ReviewStatus.AWAITING_MODERATION;
        ModerationEntry flagEntry = flags.isEmpty() ? null : autoFlagEntry(review.getStatus(), flags, now);

        // The same rules are checked again inside one atomic MongoDB update
        Review updated = reviewUpdates.applyCustomerEdit(reviewId, customerId, request.rating(), comment,
                        now.minus(EDIT_WINDOW), now, newStatus, flags, flagEntry)
                .orElseThrow(() -> editRejectedByConcurrentChange(reviewId));
        ratingStats.recompute(updated.getProviderId(), updated.getListingId());
        return ReviewMapper.toResponse(updated);
    }

    @Override
    public ReviewPageResponse getMyReviews(String customerId, int page, int size) {
        return toPage(reviewRepository.findByCustomerId(customerId, pageable(page, size)));
    }

    @Override
    public ReviewPageResponse getProviderOwnReviews(String providerId, int page, int size) {
        // Visibility policy (FR-REV-003): the provider sees only PUBLISHED reviews. A review held by the automated
        // checks (AWAITING_MODERATION) may contain contact details or links, and a REMOVED review was hidden by a
        // moderator, so neither is returned to the provider. The customer still sees their own reviews in any
        // status (getMyReviews) and moderators see everything through the moderation endpoints.
        org.springframework.data.domain.Page<Review> reviews =
                reviewRepository.findByProviderIdAndStatus(providerId, ReviewStatus.PUBLISHED, pageable(page, size));
        // S11 shows who wrote each review
        Map<String, String> names = people.namesOf(reviews.getContent().stream().map(Review::getCustomerId).toList());
        return ReviewPageResponse.from(reviews.map(r -> ReviewMapper.toResponse(r, names.get(r.getCustomerId()))));
    }

    @Override
    public ReviewResponse replyToReview(String providerId, String reviewId, ProviderReplyRequest request) {
        Review review = findReview(reviewId);
        OwnershipChecker.assertOwner(providerId, review.getProviderId());

        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw new ResourceConflictException("REVIEW_NOT_PUBLISHED", "You can only reply to a published review");
        }

        Instant now = clock.instant();
        ProviderReply reply = new ProviderReply();
        reply.setText(request.text().trim());
        reply.setRepliedAt(now);

        // Writes only the reply fields, and only while the review is still PUBLISHED
        return reviewUpdates.applyProviderReply(reviewId, providerId, reply, now)
                .map(ReviewMapper::toResponse)
                .orElseThrow(ReviewServiceImpl::concurrentUpdateConflict);
    }

    @Override
    public ReviewPageResponse getPublishedReviews(String providerId, int page, int size) {
        return toPage(reviewRepository.findByProviderIdAndStatus(providerId, ReviewStatus.PUBLISHED, pageable(page, size)));
    }

    @Override
    public RatingSummaryResponse getRatingSummary(String providerId) {
        return summarize(providerId, ratingQuery.countPublishedByRating(providerId));
    }

    private static ModerationEntry autoFlagEntry(ReviewStatus fromStatus, List<ModerationFlag> flags, Instant now) {
        ModerationEntry entry = ModerationEntry.of(ModerationAction.AUTO_FLAGGED, fromStatus,
                ReviewStatus.AWAITING_MODERATION, ModerationEntry.SYSTEM, now);
        entry.setFlags(List.copyOf(flags));
        return entry;
    }

    // ------------------------------------------------------------------ helpers

    /** Builds the summary from per-star counts; average rounded to one decimal. */
    static RatingSummaryResponse summarize(String providerId, Map<Integer, Long> countsByRating) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        long total = 0;
        long sum = 0;
        for (int stars = 5; stars >= 1; stars--) {
            long count = countsByRating.getOrDefault(stars, 0L);
            counts.put(stars, count);
            total += count;
            sum += count * stars;
        }
        double average = total == 0 ? 0.0 : Math.round((double) sum / total * 10.0) / 10.0;
        return new RatingSummaryResponse(providerId, average, total, counts);
    }

    private Review findReview(String reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("REVIEW_NOT_FOUND", "Review not found"));
    }

    private static Pageable pageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, NEWEST_FIRST);
    }

    private static ReviewPageResponse toPage(org.springframework.data.domain.Page<Review> page) {
        return ReviewPageResponse.from(page.map(ReviewMapper::toResponse));
    }

    /** The atomic edit matched nothing: explain why, using the latest stored review. */
    private ResourceConflictException editRejectedByConcurrentChange(String reviewId) {
        Optional<Review> latest = reviewRepository.findById(reviewId);
        if (latest.isPresent() && latest.get().getProviderReply() != null) {
            return new ResourceConflictException("REVIEW_NOT_EDITABLE",
                    "This review cannot be edited because the provider has already replied");
        }
        return concurrentUpdateConflict();
    }

    private static ResourceConflictException concurrentUpdateConflict() {
        return new ResourceConflictException("REVIEW_UPDATE_CONFLICT",
                "This review was changed by another request at the same time. Please reload it and try again");
    }

    private static ResourceConflictException reviewAlreadyExists() {
        return new ResourceConflictException("REVIEW_ALREADY_EXISTS", "You have already reviewed this booking");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
