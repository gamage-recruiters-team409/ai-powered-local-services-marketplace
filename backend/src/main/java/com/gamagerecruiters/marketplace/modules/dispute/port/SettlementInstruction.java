package com.gamagerecruiters.marketplace.modules.dispute.port;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

/**
 * The final, binding decision of a dispute, as given to the settlement ports. Ids and percentages only: no amounts
 * (no safe amount accessor exists yet) and no notes.
 *
 * @param idempotencyKey the same for every attempt on the same dispute (the dispute id), so a real adapter can
 *                       recognise a retry and must never apply the same settlement twice
 */
public record SettlementInstruction(
        String disputeId,
        String bookingId,
        String customerId,
        String providerId,
        ResolutionAction action,
        int customerRefundPercent,
        int providerPenaltyPercent,
        String idempotencyKey
) {

    public static SettlementInstruction from(String disputeId, String bookingId, String customerId, String providerId,
                                             DisputeDecision decision) {
        return new SettlementInstruction(disputeId, bookingId, customerId, providerId, decision.getAction(),
                decision.getCustomerRefundPercent(), decision.getProviderPenaltyPercent(), "dispute-" + disputeId);
    }
}
