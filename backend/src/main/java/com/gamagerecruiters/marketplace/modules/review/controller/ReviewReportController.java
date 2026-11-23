package com.gamagerecruiters.marketplace.modules.review.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.review.dto.ReportReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewReportResponse;
import com.gamagerecruiters.marketplace.modules.review.moderation.ReviewModerationService;
import com.gamagerecruiters.marketplace.modules.review.security.ReviewModerationAccess;

import jakarta.validation.Valid;

/** S10 – report a published review (FR-REV-003). */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/reviews")
public class ReviewReportController {

    private final ReviewModerationService moderationService;

    public ReviewReportController(ReviewModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @PostMapping("/{reviewId}/reports")
    @PreAuthorize(ReviewModerationAccess.REPORTER)
    public ResponseEntity<ReviewReportResponse> reportReview(@PathVariable String reviewId,
                                                             @Valid @RequestBody ReportReviewRequest request) {
        ReviewReportResponse report = moderationService.reportReview(
                CurrentUser.getUserId(), CurrentUser.getRole(), reviewId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(report);
    }
}
