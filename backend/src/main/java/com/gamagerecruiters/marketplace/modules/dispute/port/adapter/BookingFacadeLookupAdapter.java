package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;

/** Reads bookings through the Booking module's public facade. Its errors are not caught or changed. */
@Component
public class BookingFacadeLookupAdapter implements BookingLookupPort {

    private final BookingFacade bookingFacade;

    public BookingFacadeLookupAdapter(BookingFacade bookingFacade) {
        this.bookingFacade = bookingFacade;
    }

    @Override
    public BookingSnapshot getBooking(String bookingId) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        return new BookingSnapshot(booking.bookingId(), booking.customerId(), booking.providerId(),
                booking.status() == BookingStatus.DISPUTED);
    }
}
