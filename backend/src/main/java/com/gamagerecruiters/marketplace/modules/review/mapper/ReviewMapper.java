package com.gamagerecruiters.marketplace.modules.review.mapper;

import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;

public final class ReviewMapper {

    private ReviewMapper() {
    }

    public static ReviewResponse toResponse(Review review) {
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
                review.getCreatedAt()
        );
    }

    private static ProviderReplyResponse toReplyResponse(ProviderReply reply) {
        if (reply == null) {
            return null;
        }
        return new ProviderReplyResponse(reply.getText(), reply.getRepliedAt());
    }
}
