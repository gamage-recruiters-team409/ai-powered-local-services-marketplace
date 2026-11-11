package com.gamagerecruiters.marketplace.modules.admin.service;

import java.time.Clock;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * Super Admin management of admin accounts. Uses the auth module only through UserInternalService.
 *
 * <p>Status change flow:</p>
 * <ol>
 *   <li>Validate everything that does not need the auth module (status, reason, self change), then read the
 *       target and check it is an admin account with a different status. Any violation is rejected here and
 *       no audit record is written.</li>
 *   <li>Insert a PENDING audit record. If that fails, the status is not changed.</li>
 *   <li>Call auth's changeStatus, which applies its own rules (actor active, hierarchy, last active SUPER_ADMIN).</li>
 *   <li>Set the record to SUCCESS, or to FAILED with the error code, and re-throw the original error unchanged.</li>
 * </ol>
 */
@Service
public class AdminAccountService {

    /** The only statuses this module may set. SUSPENDED covers suspend and ban. */
    public static final Set<UserStatus> SETTABLE_STATUSES = Set.of(UserStatus.ACTIVE, UserStatus.SUSPENDED);

    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final UserInternalService userInternalService;
    private final AdminAuditLogService auditLogService;
    private final Clock clock;

    @Autowired
    public AdminAccountService(UserInternalService userInternalService, AdminAuditLogService auditLogService) {
        this(userInternalService, auditLogService, Clock.systemUTC());
    }

    AdminAccountService(UserInternalService userInternalService, AdminAuditLogService auditLogService, Clock clock) {
        this.userInternalService = userInternalService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    public AdminStatusChangeResponse changeStatus(String actorId, Role actorRole, String targetUserId,
                                                  UserStatus newStatus, String reason) {
        // 1. Rules that need no data.
        if (newStatus == null || !SETTABLE_STATUSES.contains(newStatus)) {
            throw AdminErrors.badRequest(AdminErrors.STATUS_NOT_ALLOWED,
                    "Only ACTIVE and SUSPENDED can be set from admin account management");
        }
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.length() < MIN_REASON_LENGTH || cleanReason.length() > MAX_REASON_LENGTH) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_REASON,
                    "reason must be between " + MIN_REASON_LENGTH + " and " + MAX_REASON_LENGTH + " characters");
        }
        if (actorId == null || actorId.equals(targetUserId)) {
            throw AdminErrors.forbidden(AdminErrors.SELF_STATUS_CHANGE, "You cannot change your own account status");
        }

        // 2. Rules that need the target. getById's errors (e.g. 404 USER_NOT_FOUND) pass through unchanged.
        User target = userInternalService.getById(targetUserId);
        if (!Roles.ADMIN_ROLES.contains(target.getRole())) {
            throw AdminErrors.notFound(AdminErrors.ADMIN_ACCOUNT_NOT_FOUND, "Admin account not found");
        }
        if (target.getStatus() == newStatus) {
            throw AdminErrors.badRequest(AdminErrors.STATUS_UNCHANGED, "The account already has this status");
        }

        // 3. Audit first, then the change.
        AdminAuditLog pending = auditLogService.recordPending(AdminAuditLog.pending(
                actorId, actorRole, AdminAuditAction.ADMIN_STATUS_CHANGED,
                target.getId(), target.getRole(), target.getStatus(), newStatus,
                cleanReason, clock.instant()));

        User updated;
        try {
            updated = userInternalService.changeStatus(target.getId(), newStatus);
        } catch (RuntimeException e) {
            markFailedQuietly(pending.getId(), AdminErrors.failureCodeOf(e));
            throw e;
        }

        try {
            auditLogService.markSuccess(pending.getId());
        } catch (RuntimeException e) {
            // The status change already happened; the record stays PENDING and must be reconciled.
            log.error("Status changed but audit record {} could not be set to SUCCESS", pending.getId(), e);
        }

        log.info("Admin status change applied, audit record {}", pending.getId());
        return new AdminStatusChangeResponse(
                updated.getId(), updated.getRole(), target.getStatus(), updated.getStatus(), pending.getId());
    }

    private void markFailedQuietly(String auditLogId, String failureCode) {
        try {
            auditLogService.markFailed(auditLogId, failureCode);
        } catch (RuntimeException e) {
            log.error("Audit record {} could not be set to FAILED", auditLogId, e);
        }
    }
}
