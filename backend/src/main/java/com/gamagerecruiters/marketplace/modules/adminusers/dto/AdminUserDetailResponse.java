package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import java.time.Instant;
import java.util.List;

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
        Section<List<AdminActionView>> adminActions,
        Actions actions
) {

    /**
     * What the logged-in admin may do on this page, decided by the backend (the frontend has no user id): ADMIN only
     * (SUPER_ADMIN is read-only), never on the admin's own account. The endpoints check every rule again.
     */
    public record Actions(boolean canSuspend, boolean canReactivate, boolean canReviewListings) {
    }

    /**
     * One audit record. {@code action}: SUSPENDED, REACTIVATED or STATUS_CHANGED. {@code actorName} is null when the
     * admin account cannot be read. {@code reasonCode} is set for suspensions; {@code note} is the admin's note.
     */
    public record AdminActionView(String auditLogId, String actorId, String actorName, String action,
                                  UserStatus fromStatus, UserStatus toStatus, String reasonCode, String note,
                                  String outcome, Instant createdAt) {
    }

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
