package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;

/**
 * Dispute eligibility through the Booking module's public facade.
 *
 * <h2>SWITCH-ON when BookingFacade.assertDisputeEligible is merged (WP05, Disura)</h2>
 * Replace the body of {@link #assertEligible(String)} with the line marked {@code SWITCH-ON} below (and add the
 * import {@code com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser}), then delete
 * {@link #interimAssertEligible(String)} and the interim test in {@code BookingFacadeDisputeEligibilityAdapterTest}.
 * No other code changes: the dispute service already uses this port for every raise.
 *
 * <h2>Until then (INTERIM, safe)</h2>
 * The booking is read with getBookingParticipants. A booking that is NOT yet DISPUTED is returned as is: its
 * eligibility is then enforced by BookingFacade.markDisputed in the raise flow. A booking that is ALREADY DISPUTED
 * FAILS CLOSED (409 DISPUTE_ELIGIBILITY_UNAVAILABLE), because only assertDisputeEligible can check its 72h window.
 */
@Component
public class BookingFacadeDisputeEligibilityAdapter implements DisputeEligibilityPort {

    private static final Logger log = LoggerFactory.getLogger(BookingFacadeDisputeEligibilityAdapter.class);

    private final BookingFacade bookingFacade;

    public BookingFacadeDisputeEligibilityAdapter(BookingFacade bookingFacade) {
        this.bookingFacade = bookingFacade;
    }

    @Override
    public BookingSnapshot assertEligible(String bookingId) {
        // SWITCH-ON: return toSnapshot(bookingFacade.assertDisputeEligible(bookingId, CurrentUser.getUserId()));
        return interimAssertEligible(bookingId);
    }

    /** INTERIM until assertDisputeEligible exists. 404 BOOKING_NOT_FOUND passes through unchanged. */
    private BookingSnapshot interimAssertEligible(String bookingId) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        if (booking.status() == BookingStatus.DISPUTED) {
            log.warn("event=DISPUTE_ELIGIBILITY_UNAVAILABLE bookingId={}", bookingId);
            throw new ApiException(HttpStatus.CONFLICT, "DISPUTE_ELIGIBILITY_UNAVAILABLE",
                    "This booking is already in dispute. A new dispute case cannot be opened for it yet");
        }
        return toSnapshot(booking);
    }

    static BookingSnapshot toSnapshot(BookingParticipants booking) {
        return new BookingSnapshot(booking.bookingId(), booking.customerId(), booking.providerId(),
                booking.status() == BookingStatus.DISPUTED);
    }
}
