package com.gamagerecruiters.marketplace.modules.review.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportReason;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportStatus;

/** A review report. The reporter's ID is not exposed (only their role). */
public record ReviewReportResponse(
        String id,
        String reviewId,
        Role reporterRole,
        ReportReason reason,
        String details,
        ReportStatus status,
        Instant createdAt
) {
}
