package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;

/**
 * Result of recording a case action.
 *
 * <p>{@code suspensionApplied} is true only when an ADMIN SUSPEND went through the Admin Users suspend flow.
 * {@code listingVisibilitySynced} is present only for a provider suspension: true when listings were hidden, false when
 * that update failed (the suspension still stands). {@code opsRecordPending} is true when the account was suspended but
 * the operations log insertion failed; the Admin Users audit is authoritative and the caller should not interpret this
 * as a no-op.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpsActionResult(OpsCaseType caseType, String subjectId, OpsActionType action, OpsCaseStatus caseStatus,
                               Instant recordedAt, boolean suspensionApplied, Boolean listingVisibilitySynced,
                               boolean opsRecordPending) {

    /** Backward-compatible constructor for non-suspend actions and successful suspends without partial failure. */
    public OpsActionResult(OpsCaseType caseType, String subjectId, OpsActionType action, OpsCaseStatus caseStatus,
                           Instant recordedAt, boolean suspensionApplied) {
        this(caseType, subjectId, action, caseStatus, recordedAt, suspensionApplied, null, false);
    }
}
