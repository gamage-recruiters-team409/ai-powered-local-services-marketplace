package com.gamagerecruiters.marketplace.modules.admin.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * One administrative action. Immutable from the outside: there is no update or delete endpoint, and the
 * only change after insert is the one-time outcome update (PENDING -> SUCCESS or FAILED) made by
 * {@code AdminAuditLogService}.
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
    public static AdminAuditLog pending(String actorId, Role actorRole, AdminAuditAction action,
                                        String targetUserId, Role targetRole,
                                        UserStatus fromStatus, UserStatus toStatus,
                                        String reason, Instant createdAt) {
        AdminAuditLog log = new AdminAuditLog();
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

    public String getId() {
        return id;
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
