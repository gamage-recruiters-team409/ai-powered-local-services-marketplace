package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.util.Set;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
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

    /**
     * Admin list filtered by dispute category (e.g. {@link DisputeCategory#NO_SHOW}), newest first. The summaries carry
     * the real dispute status. Read path for the operations No-show reports (WP09). Caller must be authorized for the area.
     */
    DisputePageResponse<DisputeSummaryResponse> getDisputesForAdminByCategory(DisputeCategory category, int page,
                                                                              int size);

    /** Admin detail. Caller must be authorized for the area. */
    DisputeDetailResponse getDisputeForAdmin(String disputeId);

    /**
     * Dispute content for an AI summary (WP01 AI-005). Enforces authorization: only parties to the dispute (customer
     * or provider) or authorized admins (ADMIN, SUPER_ADMIN) may access it; unauthorized callers receive 403
     * NOT_DISPUTE_PARTY. Unsynced/unknown disputes return 404 DISPUTE_NOT_FOUND.
     *
     * @param disputeId the dispute ID
     * @param actorId the ID of the calling party or admin
     * @param actorRole the role of the calling party or admin
     * @return the AI summary data
     */
    DisputeAiSummaryData getDisputeForAiSummary(String disputeId, String actorId, Role actorRole);

    /**
     * In-process / trusted system overload for dispute content for an AI summary.
     *
     * @param disputeId the dispute ID
     * @return the AI summary data
     */
    DisputeAiSummaryData getDisputeForAiSummary(String disputeId);
}
