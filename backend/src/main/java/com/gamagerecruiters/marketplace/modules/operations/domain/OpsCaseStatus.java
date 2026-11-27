package com.gamagerecruiters.marketplace.modules.operations.domain;

/**
 * The status a case holds after the latest action was recorded. A case with no recorded action is {@link #OPEN}.
 * "Resolved today" counts {@link #RESOLVED} actions on the Asia/Colombo day.
 */
public enum OpsCaseStatus {
    OPEN,
    REVIEWED,
    CONTACTED,
    FLAGGED,
    ESCALATED,
    SUSPENSION_RECOMMENDED,
    SUSPENDED,
    RESOLVED
}
