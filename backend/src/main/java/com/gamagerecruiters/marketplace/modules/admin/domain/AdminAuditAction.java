package com.gamagerecruiters.marketplace.modules.admin.domain;

/** Administrative actions recorded in the admin audit log. */
public enum AdminAuditAction {
    ADMIN_ACCOUNT_CREATED,
    ADMIN_STATUS_CHANGED,
    PLATFORM_SETTINGS_UPDATED,
    /**
     * Insert-only record written when an action was applied but its own audit record could not be completed.
     * It points to that record (relatedAuditLogId) and carries the same correlationId. Ids and codes only.
     */
    AUDIT_RECONCILIATION_REQUIRED
}
