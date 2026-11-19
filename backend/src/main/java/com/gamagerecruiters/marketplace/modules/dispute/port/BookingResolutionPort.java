package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * Moves the disputed booking to its final status (WP05): RELEASE_TO_PROVIDER -> COMPLETED, REFUND_FULL -> REFUNDED,
 * REFUND_PARTIAL -> PARTIALLY_REFUNDED. Called once per final decision, idempotently.
 *
 * <p>BookingFacade has no admin method for this yet (BookingStatus already allows DISPUTED -> COMPLETED / REFUNDED /
 * PARTIALLY_REFUNDED), so the active adapter is a stub that applies nothing and reports NOT_APPLIED.</p>
 */
public interface BookingResolutionPort {

    /** @throws RuntimeException if the change failed or its outcome is uncertain (it is retried later) */
    SettlementOutcome resolveBooking(SettlementInstruction instruction);
}
