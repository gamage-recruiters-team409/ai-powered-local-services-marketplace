package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.util.Set;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;

/**
 * Dispute use cases. Role checks happen in the controllers ({@code @PreAuthorize}); this service
 * enforces the per-dispute rules (party membership, duplicates, response window, status). Whether a booking
 * may be disputed at all is decided by the Booking module.
 */
public interface DisputeService {

    /** A customer or provider raises a dispute for a booking they are part of. */
    DisputeDetailResponse raiseDispute(String actorId, Role actorRole, RaiseDisputeRequest request);

    /** Disputes where the user is the customer or the provider, newest first. */
    DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String userId, int page, int size);

    /** As above, only in one of {@code statuses} (empty or null = every status). */
    DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String userId, Set<DisputeStatus> statuses, int page,
                                                              int size);

    /** A dispute for one of its parties; 403 for anyone else. */
    DisputeDetailResponse getDisputeForParty(String userId, String disputeId);

    /** The other party (not the one who raised it) responds before responseDueAt. */
    DisputeDetailResponse submitResponse(String actorId, Role actorRole, String disputeId,
                                         SubmitDisputeResponseRequest request);

    /** Admin list, optionally filtered by status, newest first. Caller must be authorized for the area. */
    DisputePageResponse<DisputeSummaryResponse> getDisputesForAdmin(String status, int page, int size);

    /** Admin detail. Caller must be authorized for the area. */
    DisputeDetailResponse getDisputeForAdmin(String disputeId);

    /**
     * Dispute content for an AI summary (WP01 AI-005). In-process only: there is no HTTP endpoint for it, and the
     * caller must be authorized to view the dispute. Contains no personal data, ids of the parties or admins, admin
     * notes or settlement internals (see {@link DisputeAiSummaryData}). A PENDING (unsynced) or unknown dispute:
     * 404 DISPUTE_NOT_FOUND, as in the party view.
     */
    DisputeAiSummaryData getDisputeForAiSummary(String disputeId);
}
