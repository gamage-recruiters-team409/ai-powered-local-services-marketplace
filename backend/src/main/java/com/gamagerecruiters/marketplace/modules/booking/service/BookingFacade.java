package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;

/**
 * The only entry point other modules use to read booking information.
 * Other modules must not use the booking repository directly.
 */
public interface BookingFacade {

    /**
     * Returns true if the customer has any booking in
     * PENDING_PROVIDER, AWAITING_PAYMENT, CONFIRMED, IN_PROGRESS, COMPLETION_PENDING or DISPUTED.
     * Used by customer account deactivation (FR-CUST-006).
     *
     * <p>Integration note: the customer module checks open bookings through its own
     * {@code OpenBookingsChecker} interface. The customer module owner adds a small adapter in the
     * customer module that delegates to this method, after this facade is merged into develop.
     */
    boolean hasOpenBookingsForCustomer(String customerId);

    /**
     * Returns the IDs and current status of a booking.
     *
     * @throws com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException
     *         with code BOOKING_NOT_FOUND when the booking does not exist
     */
    BookingParticipants getBookingParticipants(String bookingId);
}
