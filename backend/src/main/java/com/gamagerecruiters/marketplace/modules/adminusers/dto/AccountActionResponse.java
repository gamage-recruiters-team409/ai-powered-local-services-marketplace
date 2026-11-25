package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * Result of suspend / reactivate. {@code changed = false}: the account already had that status, nothing was changed
 * or audited (idempotent), and there is no {@code auditLogId}.
 *
 * <p>{@code listingVisibilitySynced} is present only when a provider's status changed: true when their listings were
 * hidden / restored, false when that update failed (the status change stands; retry with
 * POST /{id}/listing-visibility/sync).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountActionResponse(
        String userId,
        Role role,
        UserStatus previousStatus,
        UserStatus status,
        boolean changed,
        String auditLogId,
        Boolean listingVisibilitySynced
) {

    public AccountActionResponse(String userId, Role role, UserStatus previousStatus, UserStatus status,
                                 boolean changed, String auditLogId) {
        this(userId, role, previousStatus, status, changed, auditLogId, null);
    }
}
