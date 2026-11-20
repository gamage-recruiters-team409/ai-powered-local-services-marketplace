package com.gamagerecruiters.marketplace.modules.review.controller;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationReviewDetailResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.PublishReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.RemoveReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.moderation.ModerationQueueFilter;
import com.gamagerecruiters.marketplace.modules.review.moderation.ReviewModerationService;
import com.gamagerecruiters.marketplace.modules.review.security.ReviewModerationAccess;

import jakarta.validation.Valid;

/** S14–S16 – review moderation for the MODERATOR admin area (FR-REV-003, BR-016). */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/reviews")
public class ReviewModerationController {

    private final ReviewModerationService moderationService;

    public ReviewModerationController(ReviewModerationService moderationService) {
        this.moderationService = moderationService;
    }

    /** S14 – queue: filter ALL (default), FLAGGED or REPORTED; oldest first. */
    @GetMapping
    @PreAuthorize(ReviewModerationAccess.AREA_VIEW)
    public ResponseEntity<ModerationPageResponse> getQueue(
            @RequestParam(defaultValue = "ALL") String filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(moderationService.getQueue(parseFilter(filter), page, size));
    }

    /** S15 – the review with its open reports and moderation history. */
    @GetMapping("/{reviewId}")
    @PreAuthorize(ReviewModerationAccess.AREA_VIEW)
    public ResponseEntity<ModerationReviewDetailResponse> getDetail(@PathVariable String reviewId) {
        return ResponseEntity.ok(moderationService.getDetail(reviewId));
    }

    /** S16 – publish a held review, or keep a reported review (its reports are closed). */
    @PostMapping("/{reviewId}/publish")
    @PreAuthorize(ReviewModerationAccess.AREA_EDIT)
    public ResponseEntity<ModerationReviewResponse> publish(@PathVariable String reviewId,
                                                            @Valid @RequestBody(required = false) PublishReviewRequest request) {
        return ResponseEntity.ok(moderationService.publish(CurrentUser.getUserId(), reviewId, request));
    }

    /** S16 – remove a review with a reason (shown to the customer). */
    @PostMapping("/{reviewId}/remove")
    @PreAuthorize(ReviewModerationAccess.AREA_EDIT)
    public ResponseEntity<ModerationReviewResponse> remove(@PathVariable String reviewId,
                                                           @Valid @RequestBody RemoveReviewRequest request) {
        return ResponseEntity.ok(moderationService.remove(CurrentUser.getUserId(), reviewId, request));
    }

    private static ModerationQueueFilter parseFilter(String value) {
        try {
            return ModerationQueueFilter.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "filter must be ALL, FLAGGED or REPORTED");
        }
    }
}
