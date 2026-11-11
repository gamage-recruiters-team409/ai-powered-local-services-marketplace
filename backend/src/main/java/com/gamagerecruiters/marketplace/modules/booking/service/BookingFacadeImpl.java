package com.gamagerecruiters.marketplace.modules.booking.service;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@Service
public class BookingFacadeImpl implements BookingFacade {

    private final BookingRepository bookingRepository;

    public BookingFacadeImpl(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public boolean hasOpenBookingsForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return false;
        }
        return bookingRepository.existsByCustomerIdAndStatusIn(customerId, BookingStatus.OPEN_STATUSES);
    }

    @Override
    public BookingParticipants getBookingParticipants(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            throw new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found");
        }
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));

        return new BookingParticipants(
                booking.getId(),
                booking.getSeriesId(),
                booking.getListingId(),
                booking.getCustomerId(),
                booking.getProviderId(),
                booking.getStatus());
    }
}
