package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.util.List;

/**
 * Paginated financial breakdown of recognized provider earnings.
 *
 * Page indexes are zero-based.
 */
public record ItemizedEarningsPageResponse(
        List<ItemizedEarningsResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public ItemizedEarningsPageResponse {
        content = List.copyOf(content);
    }
}
