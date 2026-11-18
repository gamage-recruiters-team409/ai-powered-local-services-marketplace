package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable paging shape (Spring's Page is not serialized directly). */
public record DisputePageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> DisputePageResponse<T> from(Page<T> page) {
        return new DisputePageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
