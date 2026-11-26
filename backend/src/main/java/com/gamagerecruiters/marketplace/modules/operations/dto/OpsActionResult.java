package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;

/**
 * Result of recording a case action. {@code suspensionApplied} is true only when an ADMIN SUSPEND went through the
 * Admin Users suspend flow.
 */
public record OpsActionResult(OpsCaseType caseType, String subjectId, OpsActionType action, OpsCaseStatus caseStatus,
                              Instant recordedAt, boolean suspensionApplied) {
}
