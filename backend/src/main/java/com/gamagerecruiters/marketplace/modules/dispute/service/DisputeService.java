package com.gamagerecruiters.marketplace.modules.dispute.service;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;

/**
 * Dispute use cases. Role checks happen in the controllers ({@code @PreAuthorize}); this service
 * enforces the per-dispute rules (party membership, windows, status).
 */
public interface DisputeService {

    /** A customer or provider raises a dispute for a booking they are part of. */
    DisputeDetailResponse raiseDispute(String actorId, Role actorRole, RaiseDisputeRequest request);

    /** Disputes where the user is the customer or the provider, newest first. */
    DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String userId, int page, int size);

    /** A dispute for one of its parties; 403 for anyone else. */
    DisputeDetailResponse getDisputeForParty(String userId, String disputeId);

    /** The other party (not the one who raised it) responds before responseDueAt. */
    DisputeDetailResponse submitResponse(String actorId, Role actorRole, String disputeId,
                                         SubmitDisputeResponseRequest request);

    /** Admin list, optionally filtered by status, newest first. Caller must be authorized for the area. */
    DisputePageResponse<DisputeSummaryResponse> getDisputesForAdmin(String status, int page, int size);

    /** Admin detail. Caller must be authorized for the area. */
    DisputeDetailResponse getDisputeForAdmin(String disputeId);
}
