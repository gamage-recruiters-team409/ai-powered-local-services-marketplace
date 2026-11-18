package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;

/**
 * INTERIM behaviour of the eligibility adapter, until BookingFacade.assertDisputeEligible is merged.
 * When it is switched on (see the adapter's class comment), replace these tests with ones that verify
 * assertDisputeEligible(bookingId, CurrentUser.getUserId()) is called and its result mapped.
 */
@ExtendWith(MockitoExtension.class)
class BookingFacadeDisputeEligibilityAdapterTest {

    @Mock
    private BookingFacade bookingFacade;

    private BookingParticipants booking(BookingStatus status) {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");
        return new BookingParticipants("b-1", "s-1", "l-1", "c-1", "p-1", status, now, now.plusSeconds(3600));
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "IN_PROGRESS", "COMPLETION_PENDING", "COMPLETED"})
    @DisplayName("not yet DISPUTED -> returned as is (markDisputed then enforces eligibility)")
    void notDisputedPassesThrough(BookingStatus status) {
        when(bookingFacade.getBookingParticipants("b-1")).thenReturn(booking(status));

        BookingSnapshot snapshot = new BookingFacadeDisputeEligibilityAdapter(bookingFacade).assertEligible("b-1");

        assertThat(snapshot).isEqualTo(new BookingSnapshot("b-1", "c-1", "p-1", false));
    }

    @Test
    @DisplayName("already DISPUTED -> FAILS CLOSED with 409 DISPUTE_ELIGIBILITY_UNAVAILABLE (no bypass)")
    void alreadyDisputedFailsClosed() {
        when(bookingFacade.getBookingParticipants("b-1")).thenReturn(booking(BookingStatus.DISPUTED));

        Throwable thrown = catchThrowable(() -> new BookingFacadeDisputeEligibilityAdapter(bookingFacade).assertEligible("b-1"));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo("DISPUTE_ELIGIBILITY_UNAVAILABLE");
    }

    @Test
    @DisplayName("unknown booking -> the facade's 404 passes through unchanged")
    void unknownBookingPassesThrough() {
        ResourceNotFoundException notFound = new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found");
        when(bookingFacade.getBookingParticipants("b-1")).thenThrow(notFound);

        assertThat(catchThrowable(() -> new BookingFacadeDisputeEligibilityAdapter(bookingFacade).assertEligible("b-1")))
                .isSameAs(notFound);
    }

    @Test
    @DisplayName("mapping: DISPUTED status is reported as alreadyDisputed")
    void mapsDisputedStatus() {
        assertThat(BookingFacadeDisputeEligibilityAdapter.toSnapshot(booking(BookingStatus.DISPUTED)).alreadyDisputed())
                .isTrue();
        assertThat(BookingFacadeDisputeEligibilityAdapter.toSnapshot(booking(BookingStatus.CONFIRMED)).alreadyDisputed())
                .isFalse();
    }
}
