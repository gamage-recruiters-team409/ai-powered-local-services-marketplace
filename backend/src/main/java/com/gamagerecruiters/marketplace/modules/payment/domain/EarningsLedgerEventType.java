
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

    // Available earnings temporarily moved into a hold.
    EARNING_HOLD,

    // Held earnings returned to the available balance.
    EARNING_RELEASE,

    // Available earnings deducted for a payout.
    PAYOUT_DEBIT,

    // Refund deducted from earnings awaiting availability.
    REFUND_FROM_PENDING,

    // Refund deducted from available earnings.
    REFUND_FROM_AVAILABLE,

    // Refund deducted from held earnings.
    REFUND_FROM_HELD
}
