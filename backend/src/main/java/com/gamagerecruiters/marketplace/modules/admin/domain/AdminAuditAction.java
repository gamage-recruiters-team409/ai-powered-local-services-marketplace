package com.gamagerecruiters.marketplace.modules.admin.domain;

/** Administrative actions recorded in the admin audit log. */
public enum AdminAuditAction {
    /** Reserved: written by the create-account endpoint (waits for the auth module changes). */
    ADMIN_ACCOUNT_CREATED,
    ADMIN_STATUS_CHANGED
}
