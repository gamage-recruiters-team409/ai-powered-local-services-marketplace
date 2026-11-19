package com.gamagerecruiters.marketplace.modules.customer.dto;

import java.util.List;

/**
 * Customer-facing paginated favourites response (FR-CUST-005).
 */
public record CustomerFavouritePageResponse(
        List<CustomerFavouriteResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
