package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort.AdminAction;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.HiddenListings;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort.ServiceArea;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort.Rating;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort.BookingOutcomes;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort.BookingTotals;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort.Cancellations;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort.MonthCount;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort.QueuedBooking;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort.DisputeStats;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Account page of one customer or provider. Every part that comes from another module is a {@link Section}: when
 * it is not available it has no data at all (never zeros). Provider-only parts are NOT_APPLICABLE for customers.
 * Email and phone are personal data, returned only on this admin endpoint and never logged.
 */
public record AdminUserDetailResponse(
        Profile profile,
        Section<BookingTotals> bookingStats,
        Section<List<MonthCount>> bookingsPerMonth,
        Section<BookingOutcomes> bookingOutcomes,
        Section<Rating> rating,
        Section<Cancellations> cancellations,
        Section<DisputeStats> disputes,
        Section<HiddenListings> hiddenListings,
        Section<List<QueuedBooking>> queuedBookings,
        Section<List<AdminAction>> adminActions
) {

    public record Profile(
            String id,
            String displayName,
            Role role,
            UserStatus status,
            String email,
            String phone,
            Instant memberSince,
            Section<Instant> lastActive,
            boolean emailVerified,
            Section<Boolean> providerVerified,
            Section<ServiceArea> serviceArea,
            Section<Boolean> pro
    ) {
    }
}
