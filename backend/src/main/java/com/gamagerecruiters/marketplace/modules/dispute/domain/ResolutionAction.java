package com.gamagerecruiters.marketplace.modules.dispute.domain;

/** What an admin decides for the money held for the disputed booking. */
public enum ResolutionAction {

    /** The customer gets everything back. Booking ends REFUNDED. */
    REFUND_FULL,
    /** The customer gets {@code customerRefundPercent} back, the provider the rest. Booking ends PARTIALLY_REFUNDED. */
    REFUND_PARTIAL,
    /** The provider is paid in full. Booking ends COMPLETED. A provider penalty is not allowed with this action. */
    RELEASE_TO_PROVIDER;

    public boolean isRefund() {
        return this == REFUND_FULL || this == REFUND_PARTIAL;
    }
}
