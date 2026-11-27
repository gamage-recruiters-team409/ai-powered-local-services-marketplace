package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;

/**
 * Dispute eligibility through the Booking module's public facade: {@code BookingFacade.assertDisputeEligible}
 * (read-only), with the logged-in user as the actor. The Booking module owns every time rule (BR-008); the dispute
 * module adds none of its own. Errors pass through unchanged: 401, 403 ACCESS_DENIED, 404 BOOKING_NOT_FOUND,
 * 409 INVALID_STATUS_TRANSITION, 409 DISPUTE_WINDOW_CLOSED.
 */
@Component
public class BookingFacadeDisputeEligibilityAdapter implements DisputeEligibilityPort {

    private final BookingFacade bookingFacade;

    public BookingFacadeDisputeEligibilityAdapter(BookingFacade bookingFacade) {
        this.bookingFacade = bookingFacade;
    }

    @Override
    public BookingSnapshot assertEligible(String bookingId) {
        return toSnapshot(bookingFacade.assertDisputeEligible(bookingId, CurrentUser.getUserId()));
    }

    static BookingSnapshot toSnapshot(BookingParticipants booking) {
        return new BookingSnapshot(booking.bookingId(), booking.customerId(), booking.providerId(),
                booking.status() == BookingStatus.DISPUTED);
    }
}
