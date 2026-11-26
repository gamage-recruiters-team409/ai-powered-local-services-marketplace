package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** One page of bookings (same shape as the other modules' page responses). */
public record BookingPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> BookingPageResponse<T> from(Page<T> page) {
        return new BookingPageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
