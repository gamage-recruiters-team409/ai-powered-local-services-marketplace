package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;

/**
 * The only entry point other modules use to read or change booking information.
 * Other modules must not use the booking repository directly.
 *
 * <p>Status-change methods check that the actor belongs to the booking, apply only transitions
 * allowed by {@code BookingStatus}, record the change in the booking timeline and return the
 * updated booking. Errors:
 * <ul>
 *   <li>404 {@code BOOKING_NOT_FOUND} – unknown booking ({@code ResourceNotFoundException})</li>
 *   <li>403 {@code ACCESS_DENIED} – actor is not allowed for this booking ({@code AccessDeniedException})</li>
 *   <li>409 {@code INVALID_STATUS_TRANSITION} – the move is not allowed from the current status</li>
 *   <li>409 {@code BOOKING_UPDATE_CONFLICT} – another change saved the booking at the same time</li>
 * </ul>
 */
public interface BookingFacade {

    /** Actor ID used for automatic changes (for example the 48 h auto-confirm). */
    String SYSTEM_ACTOR = BookingStatusChange.SYSTEM;

    /**
     * Returns true if the customer has any booking in
     * PENDING_PROVIDER, AWAITING_PAYMENT, CONFIRMED, IN_PROGRESS, COMPLETION_PENDING or DISPUTED.
     * Used by customer account deactivation (FR-CUST-006).
     *
     * <p>Integration note: the customer module checks open bookings through its own
     * {@code OpenBookingsChecker} interface, connected by a small adapter in the customer module
     * that delegates to this method.
     */
    boolean hasOpenBookingsForCustomer(String customerId);

    /**
     * Returns the IDs, current status and time slot of a booking.
     *
     * @throws com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException
     *         with code BOOKING_NOT_FOUND when the booking does not exist
     */
    BookingParticipants getBookingParticipants(String bookingId);

    /** CONFIRMED → IN_PROGRESS. Only the booking's provider. */
    BookingParticipants markInProgress(String bookingId, String providerId);

    /** IN_PROGRESS → COMPLETION_PENDING. Only the booking's provider. */
    BookingParticipants markCompletionPending(String bookingId, String providerId);

    /**
     * COMPLETION_PENDING → COMPLETED. The booking's customer, or {@link #SYSTEM_ACTOR} for the
     * 48 h auto-confirm. Pass SYSTEM only from scheduled jobs, never from request input.
     */
    BookingParticipants markCompleted(String bookingId, String actorId);

    /**
     * Moves the booking to DISPUTED (from CONFIRMED, IN_PROGRESS, COMPLETION_PENDING or COMPLETED).
     * Only the booking's customer or provider.
     */
    BookingParticipants markDisputed(String bookingId, String actorId, String reason);
}
