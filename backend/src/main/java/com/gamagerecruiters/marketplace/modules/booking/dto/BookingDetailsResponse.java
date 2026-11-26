package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Booking Details for its customer or provider.
 * The provider sees only the district and no notes until the booking is CONFIRMED (C-08):
 * {@code addressVisible=false}, {@code address} holds the district only and {@code notes} is null.
 */
public record BookingDetailsResponse(
        String id,
        String ref,
        BookingStatus status,
        String viewerRole,
        String listingId,
        String serviceTitle,
        String customerId,
        String providerId,
        String seriesId,
        Instant slotStart,
        Instant slotEnd,
        String localDate,
        String localStart,
        String localEnd,
        int durationMinutes,
        boolean addressVisible,
        BookingAddressRequest address,
        String notes,
        Price price,
        Instant responseDeadline,
        Instant acceptedAt,
        Instant paymentDueAt,
        Instant createdAt,
        List<TimelineEntry> timeline,
        RescheduleView pendingReschedule,
        int remainingReschedules
) {

    /** Price captured at booking creation. Commission is not shown to customers or providers. */
    public record Price(
            BigDecimal basePrice,
            List<AddOn> addOns,
            BigDecimal totalPrice,
            String currency,
            String pricingModel
    ) {
    }

    public record AddOn(String addOnId, String title, BigDecimal extraPrice, int extraDurationMinutes) {
    }

    /** Status history. {@code actor}: CUSTOMER, PROVIDER, SYSTEM or ADMIN – never another user's ID. */
    public record TimelineEntry(BookingStatus fromStatus, BookingStatus toStatus, Instant changedAt, String actor,
                                String reason) {
    }
}
