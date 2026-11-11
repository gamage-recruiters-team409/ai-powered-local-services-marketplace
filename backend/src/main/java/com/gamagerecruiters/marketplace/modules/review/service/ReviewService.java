package com.gamagerecruiters.marketplace.modules.review.service;

import com.gamagerecruiters.marketplace.modules.review.dto.CreateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.UpdateReviewRequest;

/** Reviews & ratings (WP08). */
public interface ReviewService {

    /** Customer reviews a COMPLETED booking, within 7 days of completion; one review per booking. */
    ReviewResponse createReview(String customerId, CreateReviewRequest request);

    /** Customer edits their own review within 48 hours, while it has no provider reply. */
    ReviewResponse updateReview(String customerId, String reviewId, UpdateReviewRequest request);

    ReviewPageResponse getMyReviews(String customerId, int page, int size);

    /** Provider's own reviews, any status (they can see if one was removed). */
    ReviewPageResponse getProviderOwnReviews(String providerId, int page, int size);

    /** Provider adds or edits their single reply to a published review. */
    ReviewResponse replyToReview(String providerId, String reviewId, ProviderReplyRequest request);

    /** Published reviews for a provider. */
    ReviewPageResponse getPublishedReviews(String providerId, int page, int size);

    RatingSummaryResponse getRatingSummary(String providerId);
}
