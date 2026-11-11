package com.gamagerecruiters.marketplace.modules.dispute.port.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.port.BookingDisputeStatusPort;

/**
 * TEMPORARY stub until the Booking module (WP05) exists. Only logs; changes nothing.
 * TODO(WP05): replace with an adapter that calls the Booking module's service, then delete this class.
 */
@Component
public class StubBookingDisputeStatusAdapter implements BookingDisputeStatusPort {

    private static final Logger log = LoggerFactory.getLogger(StubBookingDisputeStatusAdapter.class);

    @Override
    public void markDisputed(String bookingId, String disputeId) {
        log.warn("BookingDisputeStatusPort is a stub (WP05 not integrated); booking {} NOT set to DISPUTED for dispute {}",
                bookingId, disputeId);
    }
}
