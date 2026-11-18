package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * Carries out the money side of a final decision (WP07): release to the provider, full or partial refund to the
 * customer, and the provider penalty ({@code providerPenaltyPercent}). Called once per final decision, idempotently.
 *
 * <p>Separate from {@link PayoutHoldPort} (which only holds a payout when a dispute is raised). No payout, refund or
 * penalty API exists yet, so the active adapter is a stub that applies nothing and reports NOT_APPLIED.</p>
 */
public interface DisputeSettlementPort {

    /** @throws RuntimeException if the settlement failed or its outcome is uncertain (it is retried later) */
    SettlementOutcome settle(SettlementInstruction instruction);
}
