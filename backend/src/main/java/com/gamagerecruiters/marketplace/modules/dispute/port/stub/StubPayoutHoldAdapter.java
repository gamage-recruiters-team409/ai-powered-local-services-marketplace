package com.gamagerecruiters.marketplace.modules.dispute.port.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;

/**
 * TEMPORARY stub until the Payment/Payout module (WP07, Aurudika) offers a payout-hold method. Only logs;
 * changes nothing. The payment module is not touched from here.
 * TODO(WP07): replace with an adapter that calls the Payment module's public service, then delete this class.
 */
@Component
public class StubPayoutHoldAdapter implements PayoutHoldPort {

    private static final Logger log = LoggerFactory.getLogger(StubPayoutHoldAdapter.class);

    @Override
    public void holdPayout(String bookingId, String disputeId) {
        log.warn("PayoutHoldPort is a stub (WP07 not integrated); payout for booking {} NOT put ON_HOLD for dispute {}",
                bookingId, disputeId);
    }
}
