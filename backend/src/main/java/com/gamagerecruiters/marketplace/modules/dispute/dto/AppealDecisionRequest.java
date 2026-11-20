package com.gamagerecruiters.marketplace.modules.dispute.dto;

import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The reviewing admin's decision on an appeal. UPHOLD keeps the original decision and must not carry decision
 * fields; CHANGE requires {@code action} and follows the same rules as {@link ResolveDisputeRequest}.
 */
public record AppealDecisionRequest(
        @NotNull(message = "verdict is required")
        AppealVerdict verdict,

        ResolutionAction action,

        Integer customerRefundPercent,

        Boolean applyProviderPenalty,

        @NotBlank(message = "note is required")
        @Size(max = DisputeRules.MAX_ADMIN_NOTE_LENGTH, message = "note must be at most 1000 characters")
        String note
) {
}
