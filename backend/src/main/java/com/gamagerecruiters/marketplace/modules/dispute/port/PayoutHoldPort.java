package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * What the dispute module needs from the Payment/Payout module (WP07).
 *
 * <p>The integration point for WP07 (Aurudika). Currently implemented only by {@code StubPayoutHoldAdapter}, which
 * applies NO hold. TODO(WP07): implement with a Payment module service method and remove the stub.</p>
 */
public interface PayoutHoldPort {

    /** Puts the provider payout for this booking ON_HOLD because of the given dispute. Must be idempotent. */
    void holdPayout(String bookingId, String disputeId);
}
