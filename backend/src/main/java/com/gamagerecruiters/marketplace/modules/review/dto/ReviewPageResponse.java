package com.gamagerecruiters.marketplace.modules.review.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** One page of reviews (newest first). */
public record ReviewPageResponse(
        List<ReviewResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ReviewPageResponse from(Page<ReviewResponse> page) {
        return new ReviewPageResponse(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
