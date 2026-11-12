package com.gamagerecruiters.marketplace.modules.review.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

/**
 * Provider review endpoints.
 * The public list and rating summary currently require any signed-in user; making them
 * public needs a SecurityConfig permit, which is owned by WP01 (to be agreed with Kalana).
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/providers")
public class ProviderReviewController {

    private final ReviewService reviewService;

    public ProviderReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/me/reviews")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ReviewPageResponse> getMyProviderReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(reviewService.getProviderOwnReviews(CurrentUser.getUserId(), page, size));
    }

    @GetMapping("/{providerId}/reviews")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReviewPageResponse> getPublishedReviews(
            @PathVariable String providerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(reviewService.getPublishedReviews(providerId, page, size));
    }

    @GetMapping("/{providerId}/rating-summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RatingSummaryResponse> getRatingSummary(@PathVariable String providerId) {
        return ResponseEntity.ok(reviewService.getRatingSummary(providerId));
    }
}
