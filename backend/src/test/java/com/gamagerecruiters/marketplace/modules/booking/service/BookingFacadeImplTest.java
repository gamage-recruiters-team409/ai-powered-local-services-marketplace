package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingFacadeImplTest {

    @Mock
    private BookingRepository bookingRepository;

    private BookingFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new BookingFacadeImpl(bookingRepository);
    }

    @Test
    @DisplayName("hasOpenBookingsForCustomer checks the six open statuses")
    void hasOpenBookingsUsesOpenStatuses() {
        when(bookingRepository.existsByCustomerIdAndStatusIn("cust-1", BookingStatus.OPEN_STATUSES)).thenReturn(true);

        assertThat(facade.hasOpenBookingsForCustomer("cust-1")).isTrue();
        verify(bookingRepository).existsByCustomerIdAndStatusIn("cust-1", BookingStatus.OPEN_STATUSES);
    }

    @Test
    @DisplayName("hasOpenBookingsForCustomer returns false when there are no open bookings")
    void hasOpenBookingsFalse() {
        when(bookingRepository.existsByCustomerIdAndStatusIn("cust-1", BookingStatus.OPEN_STATUSES)).thenReturn(false);

        assertThat(facade.hasOpenBookingsForCustomer("cust-1")).isFalse();
    }

    @Test
    @DisplayName("hasOpenBookingsForCustomer returns false for a blank customer ID without querying")
    void hasOpenBookingsBlankId() {
        assertThat(facade.hasOpenBookingsForCustomer(" ")).isFalse();
        assertThat(facade.hasOpenBookingsForCustomer(null)).isFalse();
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("getBookingParticipants returns IDs and status only")
    void getParticipants() {
        Booking booking = new Booking();
        booking.setId("b-1");
        booking.setSeriesId("s-1");
        booking.setListingId("l-1");
        booking.setCustomerId("c-1");
        booking.setProviderId("p-1");
        booking.setStatus(BookingStatus.CONFIRMED);
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking));

        BookingParticipants participants = facade.getBookingParticipants("b-1");

        assertThat(participants).isEqualTo(
                new BookingParticipants("b-1", "s-1", "l-1", "c-1", "p-1", BookingStatus.CONFIRMED));
    }

    @Test
    @DisplayName("getBookingParticipants throws BOOKING_NOT_FOUND for an unknown booking")
    void getParticipantsNotFound() {
        when(bookingRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.getBookingParticipants("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(e -> assertThat(((ResourceNotFoundException) e).getErrorCode())
                        .isEqualTo("BOOKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("getBookingParticipants throws BOOKING_NOT_FOUND for a blank ID without querying")
    void getParticipantsBlankId() {
        assertThatThrownBy(() -> facade.getBookingParticipants(""))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(bookingRepository);
    }
}
