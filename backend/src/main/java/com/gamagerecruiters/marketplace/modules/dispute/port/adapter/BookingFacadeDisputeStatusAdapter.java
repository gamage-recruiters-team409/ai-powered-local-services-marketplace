package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingDisputeStatusPort;

/**
 * Moves bookings to DISPUTED through the Booking module's public facade.
 *
 * <p>The actor is always the logged-in user ({@link CurrentUser#getUserId()}), never a value from the request.
 * If nobody is logged in, the facade answers 401 itself. Errors are not caught or changed.</p>
 */
@Component
public class BookingFacadeDisputeStatusAdapter implements BookingDisputeStatusPort {

    private final BookingFacade bookingFacade;

    public BookingFacadeDisputeStatusAdapter(BookingFacade bookingFacade) {
        this.bookingFacade = bookingFacade;
    }

    @Override
    public void markDisputed(String bookingId, String reason) {
        bookingFacade.markDisputed(bookingId, CurrentUser.getUserId(), reason);
    }
}
