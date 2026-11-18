package com.gamagerecruiters.marketplace.modules.dispute.dto;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin decision. {@code customerRefundPercent} is required only for REFUND_PARTIAL (1-99) and not allowed otherwise;
 * {@code applyProviderPenalty} is allowed only with REFUND_FULL or REFUND_PARTIAL (checked in the service).
 * {@code note} is internal and never shown to the parties.
 */
public record ResolveDisputeRequest(
        @NotNull(message = "action is required")
        ResolutionAction action,

        Integer customerRefundPercent,

        Boolean applyProviderPenalty,

        @NotBlank(message = "note is required")
        @Size(max = DisputeRules.MAX_ADMIN_NOTE_LENGTH, message = "note must be at most 1000 characters")
        String note
) {
}
