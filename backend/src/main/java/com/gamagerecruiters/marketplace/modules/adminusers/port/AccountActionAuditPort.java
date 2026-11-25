package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Admin audit log for suspend / reactivate (admin module, AdminAuditLogService). Same flow as the Super Admin
 * account screens: a PENDING record is written BEFORE the change (if it cannot be written the change does not run),
 * then it is completed once as SUCCESS or FAILED.
 */
public interface AccountActionAuditPort {

    record StatusChange(String actorId, Role actorRole, String targetUserId, Role targetRole,
                        UserStatus fromStatus, UserStatus toStatus, String reason) {
    }

    /** A written PENDING record. */
    interface Ticket {

        String auditLogId();

        /** The change was applied. Never throws: a record that cannot be completed is reconciled and logged. */
        void succeeded();

        /** The change was rejected (nothing changed). Never throws. */
        void failed(String failureCode);

        /**
         * The (applied) change's listing visibility update failed: recorded as listingVisibilitySync = FAILED on this
         * record. Never throws.
         */
        void listingVisibilityFailed();
    }

    /** {@code listingVisibilitySync}: null, FAILED, or SUCCESS once a retry applied it. */
    record AdminAction(String auditLogId, String actorId, String action, UserStatus fromStatus, UserStatus toStatus,
                       String reason, String outcome, Instant createdAt, String listingVisibilitySync) {
    }

    /** @throws RuntimeException if the PENDING record cannot be written (the change must then not run) */
    Ticket begin(StatusChange change);

    /** Newest first. */
    Optional<List<AdminAction>> recentForTarget(String targetUserId, int limit);

    /** A listing visibility retry succeeded: the account's FAILED markers become SUCCESS. Never throws. */
    void listingVisibilityResynced(String targetUserId);
}
