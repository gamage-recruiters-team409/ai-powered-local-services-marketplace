package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDecisionResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.DeclineBookingRequest;

/** Provider accepts or declines a PENDING_PROVIDER booking request (FR-BOOK-004). */
public interface ProviderBookingDecisionService {

    BookingDecisionResponse accept(String bookingId);

    BookingDecisionResponse decline(String bookingId, DeclineBookingRequest request);
}
