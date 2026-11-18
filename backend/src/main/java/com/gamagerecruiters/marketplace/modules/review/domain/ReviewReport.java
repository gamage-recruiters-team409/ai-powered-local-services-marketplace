package com.gamagerecruiters.marketplace.modules.review.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * A user's report about a published review (FR-REV-003, S10).
 * One report per user per review (unique index reviewId + reporterId).
 * A report does not hide the review; it puts it in the moderation queue.
 */
@Document(collection = "review_reports")
public class ReviewReport {

    @Id
    private String id;
    private String reviewId;
    private String reporterId;
    private Role reporterRole;
    private ReportReason reason;
    private String details;
    private ReportStatus status;
    private Instant createdAt;
    private Instant resolvedAt;
    private String resolvedBy;
    private ModerationAction resolution;

    public ReviewReport() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getReviewId() {
        return reviewId;
    }

    public void setReviewId(String reviewId) {
        this.reviewId = reviewId;
    }

    public String getReporterId() {
        return reporterId;
    }

    public void setReporterId(String reporterId) {
        this.reporterId = reporterId;
    }

    public Role getReporterRole() {
        return reporterRole;
    }

    public void setReporterRole(Role reporterRole) {
        this.reporterRole = reporterRole;
    }

    public ReportReason getReason() {
        return reason;
    }

    public void setReason(ReportReason reason) {
        this.reason = reason;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public ModerationAction getResolution() {
        return resolution;
    }

    public void setResolution(ModerationAction resolution) {
        this.resolution = resolution;
    }
}
