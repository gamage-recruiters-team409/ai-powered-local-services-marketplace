package com.gamagerecruiters.marketplace.modules.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.mapper.ReviewMapper;

class ReviewMapperTest {

    @Test
    void mapsReviewWithReplyToResponse() {
        Instant now = Instant.now();
        ProviderReply reply = new ProviderReply();
        reply.setText("Thank you!");
        reply.setRepliedAt(now);

        Review review = new Review();
        review.setId("r1");
        review.setBookingId("b1");
        review.setProviderId("p1");
        review.setCustomerId("c1");
        review.setRating(5);
        review.setComment("Great job");
        review.setStatus(ReviewStatus.PUBLISHED);
        review.setProviderReply(reply);
        review.setEdited(true);
        review.setCreatedAt(now);

        ReviewResponse response = ReviewMapper.toResponse(review);

        assertEquals("r1", response.id());
        assertEquals("b1", response.bookingId());
        assertEquals(5, response.rating());
        assertEquals(ReviewStatus.PUBLISHED, response.status());
        assertEquals("Thank you!", response.providerReply().text());
        assertTrue(response.edited());
    }

    @Test
    void mapsReviewWithoutReply() {
        Review review = new Review();
        review.setId("r2");
        review.setRating(3);
        review.setStatus(ReviewStatus.AWAITING_MODERATION);

        ReviewResponse response = ReviewMapper.toResponse(review);

        assertNull(response.providerReply());
        assertEquals(ReviewStatus.AWAITING_MODERATION, response.status());
    }

    @Test
    void returnsNullForNullReview() {
        assertNull(ReviewMapper.toResponse(null));
    }
}
