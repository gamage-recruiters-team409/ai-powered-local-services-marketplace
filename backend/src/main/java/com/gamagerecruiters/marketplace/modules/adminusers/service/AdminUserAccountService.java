package com.gamagerecruiters.marketplace.modules.adminusers.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminErrors;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.AdminUsersRules;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.SuspensionReason;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Suspend and reactivate CUSTOMER / PROVIDER accounts. Order of checks:
 * <ol>
 *   <li>body: suspend needs a reasonCode (note optional up to 500, required 10..500 for OTHER); reactivate needs a
 *       note of 10..500: 400 VALIDATION_FAILED (the value is never echoed or logged)</li>
 *   <li>the user directory cannot apply actions (stub): 503 USER_ACCOUNT_ACTION_UNAVAILABLE, nothing changes</li>
 *   <li>own account: 403 USER_ACTION_NOT_ALLOWED</li>
 *   <li>unknown id: 404 USER_NOT_FOUND</li>
 *   <li>ADMIN / SUPER_ADMIN / other admin roles: 403 USER_ACTION_NOT_ALLOWED</li>
 *   <li>already in the target status: 200 with changed=false (idempotent, no audit record)</li>
 *   <li>reactivate only from SUSPENDED, and never for an account that was suspended while PENDING_VERIFICATION (it
 *       must not skip email verification); a DEACTIVATED (self-closed) account cannot be suspended:
 *       409 USER_STATUS_NOT_CHANGEABLE</li>
 * </ol>
 * Then the admin audit flow: PENDING record (actor, action, reason, from/to status, time) before the change, the
 * auth module applies it with its own rules (its errors are recorded as FAILED and passed through), then SUCCESS.
 * The audit reason is {@code CODE} or {@code CODE: note} for a suspension and the note for a reactivation. "Ban" is
 * SUSPENDED; LOCKED and DEACTIVATED are never set here. Logs contain ids and statuses only, never notes.
 */
@Service
public class AdminUserAccountService {

    public static final String ACTION_UNAVAILABLE = "USER_ACCOUNT_ACTION_UNAVAILABLE";
    public static final String ACTION_NOT_ALLOWED = "USER_ACTION_NOT_ALLOWED";
    public static final String STATUS_NOT_CHANGEABLE = "USER_STATUS_NOT_CHANGEABLE";

    /** Status-change records read to find the latest suspension (the audit read's maximum). */
    static final int AUDIT_LOOKBACK = 100;

    private static final Logger log = LoggerFactory.getLogger(AdminUserAccountService.class);

    private final UserDirectoryPort directory;
    private final AccountActionAuditPort audit;

    public AdminUserAccountService(UserDirectoryPort directory, AccountActionAuditPort audit) {
        this.directory = directory;
        this.audit = audit;
    }

    public AccountActionResponse suspend(String actorId, Role actorRole, String userId, String reasonCode,
                                         String note) {
        SuspensionReason reason = SuspensionReason.parse(reasonCode).orElseThrow(() -> invalid(
                "reasonCode must be one of REPEATED_CANCELLATIONS, POLICY_VIOLATION, SUSPECTED_FRAUD, "
                        + "ABUSIVE_BEHAVIOUR, OFF_PLATFORM_CONTACT or OTHER"));
        String cleanNote = clean(note);
        if (cleanNote.length() > AdminUsersRules.MAX_REASON_LENGTH) {
            throw invalid("note must be at most " + AdminUsersRules.MAX_REASON_LENGTH + " characters");
        }
        if (reason == SuspensionReason.OTHER && cleanNote.length() < AdminUsersRules.MIN_REASON_LENGTH) {
            throw invalid("note is required for reason OTHER and must be between "
                    + AdminUsersRules.MIN_REASON_LENGTH + " and " + AdminUsersRules.MAX_REASON_LENGTH + " characters");
        }
        return change(actorId, actorRole, userId, UserStatus.SUSPENDED, SuspensionReason.auditText(reason, cleanNote));
    }

    public AccountActionResponse reactivate(String actorId, Role actorRole, String userId, String note) {
        String cleanNote = clean(note);
        if (cleanNote.length() < AdminUsersRules.MIN_REASON_LENGTH
                || cleanNote.length() > AdminUsersRules.MAX_REASON_LENGTH) {
            throw invalid("note must be between " + AdminUsersRules.MIN_REASON_LENGTH + " and "
                    + AdminUsersRules.MAX_REASON_LENGTH + " characters");
        }
        return change(actorId, actorRole, userId, UserStatus.ACTIVE, cleanNote);
    }

    private AccountActionResponse change(String actorId, Role actorRole, String userId, UserStatus newStatus,
                                         String auditReason) {
        if (!directory.supportsAccountActions()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ACTION_UNAVAILABLE,
                    "Account actions are not available yet. Nothing was changed");
        }
        if (userId != null && userId.equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ACTION_NOT_ALLOWED, "You cannot change your own account here");
        }
        if (userId == null || userId.isBlank() || userId.length() > AdminUsersRules.MAX_USER_ID_LENGTH) {
            throw AdminUsersQueryService.notFound();
        }
        UserAccount target = directory.findById(userId).orElseThrow(AdminUsersQueryService::notFound);
        if (!AdminUsersRules.MANAGED_ROLES.contains(target.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ACTION_NOT_ALLOWED,
                    "Only customer and provider accounts can be suspended or reactivated here");
        }
        if (target.status() == newStatus) {
            return new AccountActionResponse(target.id(), target.role(), target.status(), target.status(), false, null);
        }
        if (newStatus == UserStatus.ACTIVE && target.status() != UserStatus.SUSPENDED) {
            throw new ResourceConflictException(STATUS_NOT_CHANGEABLE, "Only a suspended account can be reactivated");
        }
        // An account suspended while PENDING_VERIFICATION must not become ACTIVE by being reactivated (that would
        // skip email verification). Decided from the audit log, not the emailVerified flag: with the dev setting
        // that turns verification off, normal ACTIVE accounts have emailVerified=false.
        if (newStatus == UserStatus.ACTIVE && suspendedWhilePendingVerification(target.id())) {
            throw new ResourceConflictException(STATUS_NOT_CHANGEABLE,
                    "This account was suspended before it verified its email, so it cannot be reactivated");
        }
        if (newStatus == UserStatus.SUSPENDED && target.status() == UserStatus.DEACTIVATED) {
            throw new ResourceConflictException(STATUS_NOT_CHANGEABLE, "A deactivated account cannot be suspended");
        }

        AccountActionAuditPort.Ticket ticket = audit.begin(new AccountActionAuditPort.StatusChange(
                actorId, actorRole, target.id(), target.role(), target.status(), newStatus, auditReason));
        UserAccount updated;
        try {
            updated = directory.changeStatus(target.id(), newStatus);
        } catch (RuntimeException e) {
            ticket.failed(AdminErrors.failureCodeOf(e));
            throw e;
        }
        ticket.succeeded();
        log.info("event=ADMIN_USER_STATUS_CHANGED userId={} actorId={} from={} to={} auditLogId={}",
                target.id(), actorId, target.status(), updated.status(), ticket.auditLogId());
        return new AccountActionResponse(updated.id(), updated.role(), target.status(), updated.status(), true,
                ticket.auditLogId());
    }

    /** The status the account had when it was last suspended (latest SUCCESS suspension record) was PENDING. */
    private boolean suspendedWhilePendingVerification(String userId) {
        return audit.recentForTarget(userId, AUDIT_LOOKBACK).orElse(List.of()).stream()
                .filter(a -> "SUCCESS".equals(a.outcome()) && a.toStatus() == UserStatus.SUSPENDED)
                .findFirst()
                .map(a -> a.fromStatus() == UserStatus.PENDING_VERIFICATION)
                .orElse(false);
    }

    static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
