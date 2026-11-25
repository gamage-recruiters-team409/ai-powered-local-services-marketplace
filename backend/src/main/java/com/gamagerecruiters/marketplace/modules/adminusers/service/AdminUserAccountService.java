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
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
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
 * auth module applies it atomically with its own rules and only while the account still has the status read here
 * (its errors, including 409 CONCURRENT_MODIFICATION when another admin changed it first, are recorded as FAILED and
 * passed through), then SUCCESS.
 *
 * <p>For a PROVIDER, once the status change has succeeded (never before, never after a failure) the provider's
 * listings follow it: hidden after a suspension, restored after the account is saved ACTIVE again. If that listing
 * update fails the status change is NOT rolled back: the audit record gets listingVisibilitySync = FAILED, the
 * response says {@code listingVisibilitySynced: false}, and {@link #syncListingVisibility} re-applies it.</p>
 * The audit reason is {@code CODE} or {@code CODE: note} for a suspension and the note for a reactivation. "Ban" is
 * SUSPENDED; LOCKED and DEACTIVATED are never set here. Logs contain ids and statuses only, never notes.
 */
@Service
public class AdminUserAccountService {

    public static final String ACTION_UNAVAILABLE = "USER_ACCOUNT_ACTION_UNAVAILABLE";
    public static final String ACTION_NOT_ALLOWED = "USER_ACTION_NOT_ALLOWED";
    public static final String STATUS_NOT_CHANGEABLE = "USER_STATUS_NOT_CHANGEABLE";
    public static final String LISTING_VISIBILITY_SYNC_FAILED = "LISTING_VISIBILITY_SYNC_FAILED";

    /** Status-change records read to find the latest suspension (the audit read's maximum). */
    static final int AUDIT_LOOKBACK = 100;

    /** Re-check rounds when the account's status keeps changing while its listings are being synced. */
    static final int MAX_VISIBILITY_ROUNDS = 5;

    private static final Logger log = LoggerFactory.getLogger(AdminUserAccountService.class);

    private final UserDirectoryPort directory;
    private final AccountActionAuditPort audit;
    private final ProviderListingPort listings;

    public AdminUserAccountService(UserDirectoryPort directory, AccountActionAuditPort audit,
                                   ProviderListingPort listings) {
        this.directory = directory;
        this.audit = audit;
        this.listings = listings;
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
            // Conditional on the status read above: if another admin changed it meanwhile, the source answers
            // 409 CONCURRENT_MODIFICATION, this record becomes FAILED and only the first change succeeds.
            updated = directory.changeStatus(actorId, target.id(), target.status(), newStatus);
        } catch (RuntimeException e) {
            ticket.failed(AdminErrors.failureCodeOf(e));
            throw e;
        }
        ticket.succeeded();
        log.info("event=ADMIN_USER_STATUS_CHANGED userId={} actorId={} from={} to={} auditLogId={}",
                target.id(), actorId, target.status(), updated.status(), ticket.auditLogId());
        Boolean listingsSynced = null;
        if (target.role() == Role.PROVIDER && updated.status() == newStatus) {
            listingsSynced = syncListingsToCurrentStatus(updated.id(), newStatus == UserStatus.SUSPENDED);
            if (!listingsSynced) {
                ticket.listingVisibilityFailed();
            }
        }
        return new AccountActionResponse(updated.id(), updated.role(), target.status(), updated.status(), true,
                ticket.auditLogId(), listingsSynced);
    }

    /**
     * Re-applies the provider's listing visibility for the account's CURRENT status (SUSPENDED: hidden; any other:
     * restored, which the listing module only does for an eligible ACTIVE provider). Idempotent. Customers: 403
     * USER_ACTION_NOT_ALLOWED. Listing module failure: 503 LISTING_VISIBILITY_SYNC_FAILED, nothing else changes.
     */
    public AccountActionResponse syncListingVisibility(String actorId, String userId) {
        if (userId == null || userId.isBlank() || userId.length() > AdminUsersRules.MAX_USER_ID_LENGTH) {
            throw AdminUsersQueryService.notFound();
        }
        UserAccount target = directory.findById(userId)
                .filter(u -> AdminUsersRules.MANAGED_ROLES.contains(u.role()))
                .orElseThrow(AdminUsersQueryService::notFound);
        if (target.role() != Role.PROVIDER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ACTION_NOT_ALLOWED,
                    "Listing visibility applies to provider accounts only");
        }
        if (!syncListingsToCurrentStatus(target.id(), target.status() == UserStatus.SUSPENDED)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, LISTING_VISIBILITY_SYNC_FAILED,
                    "Listing visibility could not be updated. Try again later");
        }
        audit.listingVisibilityResynced(target.id());
        log.info("event=ADMIN_USER_LISTING_VISIBILITY_SYNCED userId={} actorId={} status={}",
                target.id(), actorId, target.status());
        return new AccountActionResponse(target.id(), target.role(), target.status(), target.status(), false, null,
                true);
    }

    /**
     * Makes the provider's listings match the account's CURRENT status, not the status of the operation that started
     * the sync. The listing update is not atomic with the status change (WP03 reads the listings, checks eligibility,
     * then saves), so an older sync can finish after a newer status change: e.g. a reactivate restores listings after
     * another admin already suspended the account, and that suspension's sync found nothing to hide yet.
     *
     * <p>So after every write the account's status is read again. If it no longer matches what was just applied, the
     * visibility for the current status is applied and checked again, up to {@value #MAX_VISIBILITY_ROUNDS} rounds.
     * Every sync verifies after its own write, so whichever write lands last is followed by a check against the
     * newest status, and the final state follows the account whatever order the operations finish in. Not settled
     * (or a failure): false, the caller marks listingVisibilitySync = FAILED and the retry endpoint re-runs this.</p>
     */
    private boolean syncListingsToCurrentStatus(String providerUserId, boolean suspended) {
        boolean applying = suspended;
        for (int round = 1; round <= MAX_VISIBILITY_ROUNDS; round++) {
            if (!applyListingVisibility(providerUserId, applying)) {
                return false;
            }
            UserStatus current;
            try {
                current = directory.findById(providerUserId).map(UserAccount::status).orElse(null);
            } catch (RuntimeException e) {
                log.error("event=ADMIN_USER_LISTING_VISIBILITY_CHECK_FAILED userId={} cause={}",
                        providerUserId, e.getClass().getSimpleName());
                return false;
            }
            if (current == null) {
                return false;
            }
            boolean nowSuspended = current == UserStatus.SUSPENDED;
            if (nowSuspended == applying) {
                return true;
            }
            log.warn("event=ADMIN_USER_LISTING_VISIBILITY_RECONCILED userId={} applied={} currentStatus={} round={}",
                    providerUserId, applying ? "HIDE" : "RESTORE", current, round);
            applying = nowSuspended;
        }
        log.error("event=ADMIN_USER_LISTING_VISIBILITY_NOT_SETTLED userId={} rounds={}",
                providerUserId, MAX_VISIBILITY_ROUNDS);
        return false;
    }

    /** True when applied. A failure is logged with the id and the exception type only, never rethrown. */
    private boolean applyListingVisibility(String providerUserId, boolean suspended) {
        try {
            listings.updateSuspensionVisibility(providerUserId, suspended);
            return true;
        } catch (RuntimeException e) {
            log.error("event=ADMIN_USER_LISTING_VISIBILITY_FAILED userId={} suspended={} cause={}",
                    providerUserId, suspended, e.getClass().getSimpleName());
            return false;
        }
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
