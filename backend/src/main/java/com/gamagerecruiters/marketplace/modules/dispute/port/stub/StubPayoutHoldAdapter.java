package com.gamagerecruiters.marketplace.modules.dispute.port.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;

/**
 * STUB. NO REAL PAYOUT HOLD IS APPLIED. This class only writes a log line; the provider payout is not changed.
 *
 * <p>Deferred until the Payment/Payout module (WP07, Aurudika) offers a payout-hold method. The payment module is
 * not touched from here. The UI must not claim that a payout is on hold while this stub is in use.</p>
 * TODO(WP07): replace with an adapter that calls the Payment module's public service, then delete this class.
 */
@Component
public class StubPayoutHoldAdapter implements PayoutHoldPort {

    private static final Logger log = LoggerFactory.getLogger(StubPayoutHoldAdapter.class);

    @Override
    public void holdPayout(String bookingId, String disputeId) {
        log.warn("event=PAYOUT_HOLD_NOT_APPLIED reason=WP07_NOT_INTEGRATED bookingId={} disputeId={}",
                bookingId, disputeId);
    }
}
