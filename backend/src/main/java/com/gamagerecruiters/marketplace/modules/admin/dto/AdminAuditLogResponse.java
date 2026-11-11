package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

public record AdminAuditLogResponse(
        String id,
        String actorId,
        Role actorRole,
        AdminAuditAction action,
        String targetUserId,
        Role targetRole,
        UserStatus fromStatus,
        UserStatus toStatus,
        String reason,
        AuditOutcome outcome,
        String failureCode,
        Instant createdAt,
        Instant completedAt
) {

    public static AdminAuditLogResponse from(AdminAuditLog log) {
        return new AdminAuditLogResponse(
                log.getId(), log.getActorId(), log.getActorRole(), log.getAction(),
                log.getTargetUserId(), log.getTargetRole(), log.getFromStatus(), log.getToStatus(),
                log.getReason(), log.getOutcome(), log.getFailureCode(), log.getCreatedAt(), log.getCompletedAt());
    }
}
