package com.gamagerecruiters.marketplace.modules.dispute.port.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;

/**
 * STUB. NO REAL SETTLEMENT APPLIED: no payout is released, no refund is made and no penalty is charged. This class
 * only writes a log line (ids only) and reports NOT_APPLIED, never a success.
 *
 * <p>Deferred until the Payment/Payout module (WP07) offers release, refund and penalty methods (and an amount per
 * booking). The payment module is not touched from here.</p>
 * TODO(WP07): replace with an adapter over the Payment module's public service, then delete this class.
 */
@Component
public class StubDisputeSettlementAdapter implements DisputeSettlementPort {

    private static final Logger log = LoggerFactory.getLogger(StubDisputeSettlementAdapter.class);

    @Override
    public SettlementOutcome settle(SettlementInstruction instruction) {
        log.warn("event=DISPUTE_SETTLEMENT_NOT_APPLIED reason=WP07_NOT_INTEGRATED disputeId={} bookingId={} action={}"
                        + " customerRefundPercent={} providerPenaltyPercent={}",
                instruction.disputeId(), instruction.bookingId(), instruction.action(),
                instruction.customerRefundPercent(), instruction.providerPenaltyPercent());
        return SettlementOutcome.NOT_APPLIED;
    }
}
