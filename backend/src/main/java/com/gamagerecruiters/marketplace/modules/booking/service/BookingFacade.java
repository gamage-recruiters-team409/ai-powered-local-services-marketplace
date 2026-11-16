package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;

/**
 * The only entry point other modules use to read or change booking information.
 * Other modules must not use the booking repository directly.
 *
 * <p>User-initiated status changes are bound to the authenticated caller: the {@code actorId}
 * must be the logged-in user ({@code CurrentUser}) and a participant of the booking. Each method
 * accepts only its own starting status, records the change in the booking timeline and returns
 * the updated booking. Errors:
 * <ul>
 *   <li>401 {@code UNAUTHORIZED} – no authenticated user (user-initiated methods)</li>
 *   <li>404 {@code BOOKING_NOT_FOUND} – unknown booking ({@code ResourceNotFoundException})</li>
 *   <li>403 {@code ACCESS_DENIED} – actor is not the authenticated user or not allowed for this booking</li>
 *   <li>409 {@code INVALID_STATUS_TRANSITION} – the booking is not in the status this method starts from</li>
 *   <li>409 {@code AUTO_COMPLETION_NOT_DUE} – automatic completion requested before 48 h (BR-007)</li>
 *   <li>409 {@code DISPUTE_WINDOW_CLOSED} – dispute after the 72 h window (BR-008)</li>
 *   <li>409 {@code BOOKING_UPDATE_CONFLICT} – another change saved the booking at the same time</li>
 * </ul>
 */
public interface BookingFacade {

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

    /** CONFIRMED → IN_PROGRESS. The authenticated user must be the booking's provider. */
    BookingParticipants markInProgress(String bookingId, String providerId);

    /** IN_PROGRESS → COMPLETION_PENDING. The authenticated user must be the booking's provider. */
    BookingParticipants markCompletionPending(String bookingId, String providerId);

    /**
     * COMPLETION_PENDING → COMPLETED (customer confirms completion).
     * The authenticated user must be the booking's customer. A DISPUTED booking cannot be
     * completed here; dispute resolution stays with the admin dispute workflow.
     */
    BookingParticipants markCompleted(String bookingId, String customerId);

    /**
     * COMPLETION_PENDING → COMPLETED by the system (48 h auto-confirm, BR-007).
     * Trusted internal path for scheduled jobs only: rejected when called during an HTTP request
     * or with an authenticated user, and only allowed 48 h after the booking entered COMPLETION_PENDING.
     * Recorded as SYSTEM in the timeline.
     */
    BookingParticipants markCompletedBySystem(String bookingId);

    /**
     * Moves the booking to DISPUTED from CONFIRMED, IN_PROGRESS or COMPLETION_PENDING, or from
     * COMPLETED within 72 h of completion (BR-008). The authenticated user must be the booking's
     * customer or provider.
     */
    BookingParticipants markDisputed(String bookingId, String actorId, String reason);
}
