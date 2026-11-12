package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable paging shape for admin list endpoints (Spring's Page is not serialized directly). */
public record AdminPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> AdminPageResponse<T> from(Page<T> page) {
        return new AdminPageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
