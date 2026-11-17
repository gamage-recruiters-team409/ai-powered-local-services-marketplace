package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;

public interface BookingRequestService {

    /** Creates a PENDING_PROVIDER booking for the logged-in customer. */
    BookingResponse createBookingRequest(CreateBookingRequest request);
}
