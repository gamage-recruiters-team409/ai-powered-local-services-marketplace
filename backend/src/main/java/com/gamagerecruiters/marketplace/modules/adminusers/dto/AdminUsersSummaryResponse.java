package com.gamagerecruiters.marketplace.modules.adminusers.dto;

/**
 * Header cards of the admin users screen. Each card is a {@link Section}: available=false means no number at all.
 * {@code joinedLast7Days} is its own section, available only when the user directory can count by join date.
 */
public record AdminUsersSummaryResponse(
        Section<Count> customers,
        Section<Count> providers,
        Section<Count> suspended
) {

    public record Count(long total, Section<Long> joinedLast7Days) {
    }
}
