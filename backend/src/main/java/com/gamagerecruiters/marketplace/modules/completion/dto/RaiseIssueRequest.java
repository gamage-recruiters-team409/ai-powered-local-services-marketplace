package com.gamagerecruiters.marketplace.modules.completion.dto;

import com.gamagerecruiters.marketplace.modules.completion.domain.IssueType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Customer raises an issue during the 48-hour confirmation window (FR-COMP-004).
 * issueType is optional (OTHER when missing); it chooses the dispute category (WP09).
 */
public record RaiseIssueRequest(
        @NotBlank(message = "Please describe the issue")
        @Size(min = 10, max = 500, message = "The issue description must be between 10 and 500 characters")
        String reason,

        IssueType issueType
) {

    public RaiseIssueRequest(String reason) {
        this(reason, null);
    }
}
