package com.gamagerecruiters.marketplace.modules.announcement.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable paging shape (the Spring Page type is not serialized directly). */
public record AnnouncementPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> AnnouncementPageResponse<T> from(Page<T> page) {
        return new AnnouncementPageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
