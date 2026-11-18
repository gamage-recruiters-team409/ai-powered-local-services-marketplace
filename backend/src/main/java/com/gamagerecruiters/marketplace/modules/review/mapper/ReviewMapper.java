package com.gamagerecruiters.marketplace.modules.review.mapper;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.review.domain.ModerationEntry;
import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewReport;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationEntryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewReportResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;

public final class ReviewMapper {

    private ReviewMapper() {
    }

    public static ReviewResponse toResponse(Review review) {
        return toResponse(review, null);
    }

    /** customerName is only passed for the provider's own list (S11). */
    public static ReviewResponse toResponse(Review review, String customerName) {
        if (review == null) {
            return null;
        }
        return new ReviewResponse(
                review.getId(),
                review.getBookingId(),
                review.getListingId(),
                review.getProviderId(),
                review.getRating(),
                review.getComment(),
                review.getStatus(),
                toReplyResponse(review.getProviderReply()),
                review.isEdited(),
                review.getEditedAt(),
                review.getCreatedAt(),
                customerName,
                removalReason(review)
        );
    }

    public static ModerationReviewResponse toModerationResponse(Review review, String providerName, String customerName) {
        return new ModerationReviewResponse(
                review.getId(),
                review.getBookingId(),
                review.getListingId(),
                review.getProviderId(),
                providerName,
                review.getCustomerId(),
                customerName,
                review.getRating(),
                review.getComment(),
                review.getStatus(),
                review.getModerationFlags() == null ? List.of() : List.copyOf(review.getModerationFlags()),
                review.getOpenReportCount(),
                review.isEdited(),
                toReplyResponse(review.getProviderReply()),
                removalReason(review),
                review.getCreatedAt()
        );
    }

    public static ReviewReportResponse toReportResponse(ReviewReport report) {
        return new ReviewReportResponse(
                report.getId(),
                report.getReviewId(),
                report.getReporterRole(),
                report.getReason(),
                report.getDetails(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }

    public static ModerationEntryResponse toEntryResponse(ModerationEntry entry) {
        return new ModerationEntryResponse(
                entry.getAction(),
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getFlags() == null ? List.of() : List.copyOf(entry.getFlags()),
                entry.getReasonCode(),
                entry.getNote(),
                entry.getActorId(),
                entry.getAt()
        );
    }

    /** The reason shown for a removed review; null for any other status. */
    private static String removalReason(Review review) {
        if (review.getStatus() != ReviewStatus.REMOVED) {
            return null;
        }
        if (review.getRemovalReason() != null) {
            return review.getRemovalReason().getLabel();
        }
        return review.getStatusReason();
    }

    private static ProviderReplyResponse toReplyResponse(ProviderReply reply) {
        if (reply == null) {
            return null;
        }
        return new ProviderReplyResponse(reply.getText(), reply.getRepliedAt());
    }
}
