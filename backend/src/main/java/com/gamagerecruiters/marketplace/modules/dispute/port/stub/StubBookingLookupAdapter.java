package com.gamagerecruiters.marketplace.modules.dispute.port.stub;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;

/**
 * TEMPORARY stub until the Booking module (WP05) exists.
 *
 * <p>Always reports "booking not found", so no dispute can be raised against made-up booking data.
 * TODO(WP05): replace with an adapter that calls the Booking module's service, then delete this class.</p>
 */
@Component
public class StubBookingLookupAdapter implements BookingLookupPort {

    private static final Logger log = LoggerFactory.getLogger(StubBookingLookupAdapter.class);

    @Override
    public Optional<BookingSnapshot> findBooking(String bookingId) {
        log.warn("BookingLookupPort is a stub (WP05 not integrated); booking {} reported as not found", bookingId);
        return Optional.empty();
    }
}
