package com.gamagerecruiters.marketplace.modules.customer.service;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;

/**
 * Adapter that connects customer account deactivation (FR-CUST-006) to the booking module
 * via the {@link BookingFacade} interface.
 *
 * <p>Spring {@link org.springframework.dao.DataAccessException} is intentionally allowed
 * to propagate uncaught so that {@link CustomerServiceImpl#deactivateAccount} fails closed.
 */
@Component
public class BookingOpenBookingsChecker implements OpenBookingsChecker {

    private final BookingFacade bookingFacade;

    public BookingOpenBookingsChecker(BookingFacade bookingFacade) {
        this.bookingFacade = bookingFacade;
    }

    @Override
    public boolean hasOpenBookings(String customerId) {
        return bookingFacade.hasOpenBookingsForCustomer(customerId);
    }
}
