package com.gamagerecruiters.marketplace.modules.admin.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * One administrative action. Immutable from the outside: there is no update or delete endpoint, and the
 * only change after insert is the one-time outcome update (PENDING -> SUCCESS or FAILED) made by
 * {@code AdminAuditLogService}. AUDIT_RECONCILIATION_REQUIRED records are complete at insert and never updated.
 *
 * <p>Never stores passwords, tokens, secrets or emails. Only ids, roles, statuses, the reason and an
 * error code.</p>
 */
@Document(collection = "admin_audit_logs")
public class AdminAuditLog {

    /** Field names used in queries and the one-time outcome update. */
    public static final String FIELD_ID = "_id";
    public static final String FIELD_ACTOR_ID = "actorId";
    public static final String FIELD_TARGET_USER_ID = "targetUserId";
    public static final String FIELD_ACTION = "action";
    public static final String FIELD_OUTCOME = "outcome";
    public static final String FIELD_FAILURE_CODE = "failureCode";
    public static final String FIELD_COMPLETED_AT = "completedAt";
    public static final String FIELD_CREATED_AT = "createdAt";

    @Id
    private String id;
    /** Random id generated once per request; shared by the action's record and any reconciliation record. */
    private String correlationId;
    /** Only on AUDIT_RECONCILIATION_REQUIRED records: the id of the record that could not be completed. */
    private String relatedAuditLogId;
    private String actorId;
    private Role actorRole;
    private AdminAuditAction action;
    private String targetUserId;
    private Role targetRole;
    private UserStatus fromStatus;
    private UserStatus toStatus;
    private String reason;
    private AuditOutcome outcome;
    private String failureCode;
    private Instant createdAt;
    private Instant completedAt;

    /** For the MongoDB mapper. */
    protected AdminAuditLog() {
    }

    /** A new PENDING record for an action that is about to run. */
    public static AdminAuditLog pending(String correlationId, String actorId, Role actorRole, AdminAuditAction action,
                                        String targetUserId, Role targetRole,
                                        UserStatus fromStatus, UserStatus toStatus,
                                        String reason, Instant createdAt) {
        AdminAuditLog log = new AdminAuditLog();
        log.correlationId = correlationId;
        log.actorId = actorId;
        log.actorRole = actorRole;
        log.action = action;
        log.targetUserId = targetUserId;
        log.targetRole = targetRole;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.reason = reason;
        log.outcome = AuditOutcome.PENDING;
        log.createdAt = createdAt;
        return log;
    }

    /**
     * A complete, insert-only record saying that {@code original}'s action was applied but its own record
     * could not be set to SUCCESS. It is never updated: outcome FAILED and completedAt are set at insert,
     * failureCode holds the reason code. It copies only ids, roles and statuses from the original (never
     * the free-text reason) and takes the target id from the action's result, which the original may lack.
     */
    public static AdminAuditLog reconciliation(AdminAuditLog original, String targetUserId,
                                               String reasonCode, Instant createdAt) {
        AdminAuditLog log = new AdminAuditLog();
        log.correlationId = original.correlationId;
        log.relatedAuditLogId = original.id;
        log.actorId = original.actorId;
        log.actorRole = original.actorRole;
        log.action = AdminAuditAction.AUDIT_RECONCILIATION_REQUIRED;
        log.targetUserId = targetUserId;
        log.targetRole = original.targetRole;
        log.fromStatus = original.fromStatus;
        log.toStatus = original.toStatus;
        log.outcome = AuditOutcome.FAILED;
        log.failureCode = reasonCode;
        log.createdAt = createdAt;
        log.completedAt = createdAt;
        return log;
    }

    public String getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getRelatedAuditLogId() {
        return relatedAuditLogId;
    }

    public String getActorId() {
        return actorId;
    }

    public Role getActorRole() {
        return actorRole;
    }

    public AdminAuditAction getAction() {
        return action;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public Role getTargetRole() {
        return targetRole;
    }

    public UserStatus getFromStatus() {
        return fromStatus;
    }

    public UserStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
