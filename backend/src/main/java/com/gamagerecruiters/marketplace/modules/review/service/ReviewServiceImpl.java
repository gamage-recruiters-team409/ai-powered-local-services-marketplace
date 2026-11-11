package com.gamagerecruiters.marketplace.modules.review.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;

/**
 * Review rules (agreed for WP08):
 * - only the booking's customer can review, only when the booking is COMPLETED;
 * - within 7 days of completion; one review per booking (unique bookingId index);
 * - a new review is PUBLISHED straight away (moderation/reports can remove it later, WP09);
 * - the customer can edit within 48 hours, and not after the provider has replied;
 * - the provider has one reply per published review and can edit it;
 * - public lists and the rating summary use PUBLISHED reviews only, newest first.
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
    private final Clock clock;

    @Autowired
    public ReviewServiceImpl(ReviewRepository reviewRepository, BookingFacade bookingFacade,
                             CompletionLookup completionLookup, ReviewRatingQuery ratingQuery) {
        this(reviewRepository, bookingFacade, completionLookup, ratingQuery, Clock.systemUTC());
    }

    ReviewServiceImpl(ReviewRepository reviewRepository, BookingFacade bookingFacade,
                      CompletionLookup completionLookup, ReviewRatingQuery ratingQuery, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.bookingFacade = bookingFacade;
        this.completionLookup = completionLookup;
        this.ratingQuery = ratingQuery;
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
        Optional<Instant> completedAt = completionLookup.findConfirmedAt(booking.bookingId());
        if (completedAt.isPresent() && now.isAfter(completedAt.get().plus(REVIEW_WINDOW))) {
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
        review.setStatus(ReviewStatus.PUBLISHED);
        review.setEdited(false);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);

        try {
            return ReviewMapper.toResponse(reviewRepository.save(review));
        } catch (DuplicateKeyException ex) {
            // Unique bookingId index: a concurrent request already created the review
            throw reviewAlreadyExists();
        }
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
        if (review.getCreatedAt() != null && now.isAfter(review.getCreatedAt().plus(EDIT_WINDOW))) {
            throw new ResourceConflictException("REVIEW_EDIT_WINDOW_CLOSED",
                    "Reviews can be edited within 48 hours of posting");
        }

        review.setRating(request.rating());
        review.setComment(trimToNull(request.comment()));
        review.setEdited(true);
        review.setEditedAt(now);
        review.setUpdatedAt(now);
        return ReviewMapper.toResponse(reviewRepository.save(review));
    }

    @Override
    public ReviewPageResponse getMyReviews(String customerId, int page, int size) {
        return toPage(reviewRepository.findByCustomerId(customerId, pageable(page, size)));
    }

    @Override
    public ReviewPageResponse getProviderOwnReviews(String providerId, int page, int size) {
        return toPage(reviewRepository.findByProviderId(providerId, pageable(page, size)));
    }

    @Override
    public ReviewResponse replyToReview(String providerId, String reviewId, ProviderReplyRequest request) {
        Review review = findReview(reviewId);
        OwnershipChecker.assertOwner(providerId, review.getProviderId());

        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw new ResourceConflictException("REVIEW_NOT_PUBLISHED", "You can only reply to a published review");
        }

        Instant now = clock.instant();
        ProviderReply reply = review.getProviderReply() != null ? review.getProviderReply() : new ProviderReply();
        reply.setText(request.text().trim());
        reply.setRepliedAt(now);
        review.setProviderReply(reply);
        review.setUpdatedAt(now);
        return ReviewMapper.toResponse(reviewRepository.save(review));
    }

    @Override
    public ReviewPageResponse getPublishedReviews(String providerId, int page, int size) {
        return toPage(reviewRepository.findByProviderIdAndStatus(providerId, ReviewStatus.PUBLISHED, pageable(page, size)));
    }

    @Override
    public RatingSummaryResponse getRatingSummary(String providerId) {
        return summarize(providerId, ratingQuery.countPublishedByRating(providerId));
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
