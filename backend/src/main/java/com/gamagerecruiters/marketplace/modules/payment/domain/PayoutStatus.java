
package com.gamagerecruiters.marketplace.modules.payment.domain;

/**
 * Lifecycle states of an admin-controlled manual payout.
 */
public enum PayoutStatus {

    // Request created; bank transfer has not started.
    PENDING,

    // Admin is processing the external bank transfer.
    PROCESSING,

    // External bank transfer has been confirmed.
    PAID,

    // Transfer failed; reserved funds require recovery.
    FAILED
}
