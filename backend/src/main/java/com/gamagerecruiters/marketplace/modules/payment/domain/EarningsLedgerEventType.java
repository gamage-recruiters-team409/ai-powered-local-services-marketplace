
package com.gamagerecruiters.marketplace.modules.payment.domain;

/**
 * Types of financial events recorded in the provider earnings ledger.
 *
 * <p>
 * Ledger entries are intended to be append-only. These values
 * describe financial events, not BookingStatus or PaymentStatus.
 * </p>
 *
 * <p>
 * Refund events identify the balance from which earnings are
 * deducted, allowing deterministic balance calculations.
 * </p>
 */
public enum EarningsLedgerEventType {

    // Earnings recorded after a qualifying payment event.
    EARNING_PENDING,

    // Earnings transferred from pending to available.
    EARNING_AVAILABLE,

    // Available earnings temporarily moved into a dispute hold.
    EARNING_HOLD,

    // Pending earnings reserved for an active booking dispute.
    EARNING_HOLD_FROM_PENDING,

    // Held earnings returned to the available balance.
    EARNING_RELEASE,

    // Held earnings returned to pending after dispute resolution.
    EARNING_RELEASE_TO_PENDING,

    // Legacy direct payout debit: available earnings become paid out.
    PAYOUT_DEBIT,

    // Available earnings reserved for a pending manual payout.
    PAYOUT_RESERVE,

    // Reserved payout funds returned to available earnings.
    PAYOUT_RELEASE,

    // Reserved funds settled after confirmed external bank transfer.
    PAYOUT_SETTLE,

    // Refund deducted from earnings awaiting availability.
    REFUND_FROM_PENDING,

    // Refund deducted from available earnings.
    REFUND_FROM_AVAILABLE,

    // Refund deducted from held earnings.
    REFUND_FROM_HELD
}
