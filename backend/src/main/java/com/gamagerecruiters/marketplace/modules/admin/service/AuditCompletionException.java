package com.gamagerecruiters.marketplace.modules.admin.service;

import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;

/**
 * Thrown by {@link AdminAuditLogService} when an audit record could not be set from PENDING to its final
 * outcome: the conditional update changed no record (it was missing or no longer PENDING), changed an
 * unexpected number of records, or the database call itself failed.
 *
 * <p>The message holds only the audit record id and the outcome that was attempted. The database error, if
 * any, is kept as the cause but callers log only its type, never its message (it can contain host names).</p>
 */
public class AuditCompletionException extends RuntimeException {

    /** Why the completion did not apply. Ids and codes only. */
    public enum Reason {
        /** The update matched no PENDING record (already completed, or the record is missing). */
        NOT_PENDING,
        /** The update reported a modified count other than 0 or 1. */
        UNEXPECTED_COUNT,
        /** The database call threw. */
        DATABASE_ERROR
    }

    private final String auditLogId;
    private final AuditOutcome attemptedOutcome;
    private final Reason reason;

    public AuditCompletionException(String auditLogId, AuditOutcome attemptedOutcome, Reason reason, Throwable cause) {
        super("Audit record " + auditLogId + " could not be set to " + attemptedOutcome + " (" + reason + ")", cause);
        this.auditLogId = auditLogId;
        this.attemptedOutcome = attemptedOutcome;
        this.reason = reason;
    }

    public String getAuditLogId() {
        return auditLogId;
    }

    public AuditOutcome getAttemptedOutcome() {
        return attemptedOutcome;
    }

    public Reason getReason() {
        return reason;
    }
}
