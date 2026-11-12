package com.gamagerecruiters.marketplace.modules.completion.dto;

import jakarta.validation.constraints.Size;

/** Customer or provider reports that the other party did not show up (FR-COMP-006). */
public record ReportNoShowRequest(
        @Size(max = 500, message = "The note must not exceed 500 characters")
        String note
) {
}
