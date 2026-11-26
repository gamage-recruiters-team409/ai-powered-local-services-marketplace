package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.util.List;

/** Stable paging shape for operations lists that page an in-memory, source-capped result set. */
public record OpsPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /** Slice {@code all} (already filtered and ordered) to the requested zero-based page and size. */
    public static <T> OpsPage<T> of(List<T> all, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        int total = all.size();
        int totalPages = (int) Math.ceil(total / (double) safeSize);
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new OpsPage<>(List.copyOf(all.subList(from, to)), safePage, safeSize, total, totalPages);
    }
}
