package com.gamagerecruiters.marketplace.modules.auth.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

public final class AdminRolePolicy {

    private AdminRolePolicy() {
    }

    /**
     * Determines whether an actor with actorRole can manage a target with targetRole.
     * - SUPER_ADMIN can manage any role (self-modification is rejected separately).
     * - ADMIN can manage ONLY CUSTOMER and PROVIDER accounts.
     * - All other roles (SUPPORT_ADMIN, FINANCE_ADMIN, MODERATOR, CUSTOMER, PROVIDER) cannot change account status.
     */
    public static boolean canManage(Role actorRole, Role targetRole) {
        if (actorRole == null || targetRole == null) {
            return false;
        }
        if (actorRole == Role.SUPER_ADMIN) {
            return true;
        }
        if (actorRole == Role.ADMIN) {
            return targetRole == Role.CUSTOMER || targetRole == Role.PROVIDER;
        }
        return false;
    }

    /**
     * Validates that an actor may change the target user's status to newStatus.
     * Throws ApiException with a distinct error code if the operation violates the administrative hierarchy,
     * or AccessDeniedException if the actor/target could not be resolved.
     */
    public static void validateStatusChange(User actor, User target, UserStatus newStatus, UserRepository userRepository) {
        if (actor == null) {
            throw new AccessDeniedException("Authentication required: actor could not be resolved from security context");
        }
        if (target == null) {
            throw new AccessDeniedException("Target user is required");
        }
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACTOR_ACCOUNT_INACTIVE",
                    "Actor account must be active to modify user status");
        }
        if (actor.getId() != null && actor.getId().equals(target.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_SELF",
                    "Self status modification is not allowed");
        }
        if (!canManage(actor.getRole(), target.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_HIERARCHY_VIOLATION",
                    "Actor role '" + actor.getRole() + "' is not permitted to modify account status of target role '" + target.getRole() + "'");
        }
        if (target.getStatus() == UserStatus.DEACTIVATED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DEACTIVATED",
                    "Cannot modify status of a DEACTIVATED account");
        }
        if (target.getRole() == Role.SUPER_ADMIN && target.getStatus() == UserStatus.ACTIVE && newStatus != UserStatus.ACTIVE) {
            long activeSuperAdmins = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            if (activeSuperAdmins <= 1) {
                throw new ApiException(HttpStatus.CONFLICT, "LAST_ACTIVE_SUPER_ADMIN",
                        "Cannot deactivate, suspend or lock the last remaining ACTIVE SUPER_ADMIN");
            }
        }
    }
}
