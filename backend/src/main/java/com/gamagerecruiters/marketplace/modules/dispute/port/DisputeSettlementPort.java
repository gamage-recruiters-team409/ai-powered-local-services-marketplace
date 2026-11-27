package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * Carries out the money side of a final decision (WP07): release to the provider, full or partial refund to the
 * customer, and the provider penalty ({@code providerPenaltyPercent}). Separate from {@link PayoutHoldPort} (which
 * only holds a payout when a dispute is raised). Until the WP07 adapter (marked {@code @Primary}) is merged, the active
 * adapter is a stub that applies nothing and reports NOT_APPLIED.
 *
 * <h2>Contract (agreed with WP07)</h2>
 * <ul>
 *   <li>Return {@link SettlementOutcome#APPLIED} only AFTER the change is committed.</li>
 *   <li>Idempotent per {@link SettlementInstruction#idempotencyKey()} ({@code dispute-<disputeId>}): a call with a key
 *       that was already applied applies nothing again and returns the same result (APPLIED).</li>
 *   <li>Return {@link SettlementOutcome#NOT_APPLIED} only when nothing was applied because the integration is not
 *       connected yet.</li>
 *   <li>Throw {@link SettlementRejectedException} (with a {@code reasonCode}) for a PERMANENT rejection that a retry
 *       cannot fix. The dispute is then REJECTED, never retried, and the booking is not changed.</li>
 *   <li>Any other exception means a TEMPORARY failure or an uncertain outcome: the caller retries later with the same
 *       idempotency key (with backoff, up to {@code app.dispute.settlement.max-attempts}).</li>
 * </ul>
 * The dispute module calls this port first and the {@link BookingResolutionPort} only after it returned APPLIED.
 * REFUND_FULL, REFUND_PARTIAL and the provider penalty are not sent here yet (pending Phase 04 integration).
 */
public interface DisputeSettlementPort {

    /**
     * @throws SettlementRejectedException for a permanent rejection
     * @throws RuntimeException            for a temporary failure or an uncertain outcome (retried later)
     */
    SettlementOutcome settle(SettlementInstruction instruction);
}
