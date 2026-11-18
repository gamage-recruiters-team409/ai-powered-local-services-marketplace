package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitDisputeResponseRequest(
        @NotBlank(message = "text is required")
        @Size(max = DisputeRules.MAX_DESCRIPTION_LENGTH, message = "text must be at most 2000 characters")
        String text,

        @Size(max = DisputeRules.MAX_EVIDENCE_ITEMS, message = "at most 10 evidence references are allowed")
        List<@NotBlank(message = "evidence reference must not be blank")
             @Size(max = DisputeRules.MAX_EVIDENCE_REFERENCE_LENGTH,
                   message = "evidence reference must be at most 500 characters") String> evidence
) {
}
