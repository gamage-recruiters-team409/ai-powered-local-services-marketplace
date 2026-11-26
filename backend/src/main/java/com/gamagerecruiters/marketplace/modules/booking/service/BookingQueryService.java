package com.gamagerecruiters.marketplace.modules.booking.service;

import java.util.Set;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDetailsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingListItem;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPageResponse;

/** My Bookings and Booking Details for the logged-in customer or provider (FR-BOOK-006, FR-CUST-003). */
public interface BookingQueryService {

    BookingPageResponse<BookingListItem> listCustomerBookings(Set<BookingStatus> statuses, int page, int size);

    BookingPageResponse<BookingListItem> listProviderBookings(Set<BookingStatus> statuses, int page, int size);

    BookingDetailsResponse getBookingDetails(String bookingId);
}
