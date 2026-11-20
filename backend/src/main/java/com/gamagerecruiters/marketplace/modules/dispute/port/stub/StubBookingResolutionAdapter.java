package com.gamagerecruiters.marketplace.modules.dispute.port.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.port.BookingResolutionPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;

/**
 * STUB. NO REAL SETTLEMENT APPLIED: the booking stays DISPUTED. This class only writes a log line (ids only) and
 * reports NOT_APPLIED, never a success.
 *
 * <p>Deferred until the Booking module (WP05) offers an admin method to move a DISPUTED booking to COMPLETED,
 * REFUNDED or PARTIALLY_REFUNDED. The booking module is not touched from here.</p>
 * TODO(WP05): replace with an adapter over that BookingFacade method, then delete this class.
 */
@Component
public class StubBookingResolutionAdapter implements BookingResolutionPort {

    private static final Logger log = LoggerFactory.getLogger(StubBookingResolutionAdapter.class);

    @Override
    public SettlementOutcome resolveBooking(SettlementInstruction instruction) {
        log.warn("event=BOOKING_RESOLUTION_NOT_APPLIED reason=WP05_NOT_INTEGRATED disputeId={} bookingId={} action={}",
                instruction.disputeId(), instruction.bookingId(), instruction.action());
        return SettlementOutcome.NOT_APPLIED;
    }
}
