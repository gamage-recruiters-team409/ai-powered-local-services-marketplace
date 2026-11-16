package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;

@ExtendWith(MockitoExtension.class)
class BookingOpenBookingsCheckerTest {

    @Mock
    private BookingFacade bookingFacade;

    private BookingOpenBookingsChecker checker;

    @BeforeEach
    void setUp() {
        checker = new BookingOpenBookingsChecker(bookingFacade);
    }

    @Test
    @DisplayName("hasOpenBookings returns true when booking facade reports open bookings exist")
    void shouldReturnTrueWhenOpenBookingsExist() {
        String customerId = "cust-100";
        when(bookingFacade.hasOpenBookingsForCustomer(customerId)).thenReturn(true);

        boolean result = checker.hasOpenBookings(customerId);

        assertTrue(result);
        verify(bookingFacade).hasOpenBookingsForCustomer(customerId);
    }

    @Test
    @DisplayName("hasOpenBookings returns false when booking facade reports no open bookings")
    void shouldReturnFalseWhenNoOpenBookings() {
        String customerId = "cust-200";
        when(bookingFacade.hasOpenBookingsForCustomer(customerId)).thenReturn(false);

        boolean result = checker.hasOpenBookings(customerId);

        assertFalse(result);
        verify(bookingFacade).hasOpenBookingsForCustomer(customerId);
    }

    @Test
    @DisplayName("hasOpenBookings propagates DataAccessException when booking facade throws")
    void shouldPropagateDataAccessExceptionWhenFacadeFails() {
        String customerId = "cust-300";
        DataAccessException dbException = new TransientDataAccessResourceException("Mongo connection timeout");
        when(bookingFacade.hasOpenBookingsForCustomer(customerId)).thenThrow(dbException);

        DataAccessException thrown = assertThrows(
                DataAccessException.class,
                () -> checker.hasOpenBookings(customerId)
        );

        assertEquals("Mongo connection timeout", thrown.getMessage());
        verify(bookingFacade).hasOpenBookingsForCustomer(customerId);
    }
}
