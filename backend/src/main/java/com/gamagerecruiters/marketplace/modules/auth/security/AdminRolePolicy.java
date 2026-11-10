package com.gamagerecruiters.marketplace.modules.auth.security;

import org.springframework.security.access.AccessDeniedException;

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
     * Throws AccessDeniedException if the operation violates the administrative hierarchy.
     */
    public static void validateStatusChange(User actor, User target, UserStatus newStatus, UserRepository userRepository) {
        if (actor == null) {
            throw new AccessDeniedException("Authentication required: actor could not be resolved from security context");
        }
        if (target == null) {
            throw new AccessDeniedException("Target user is required");
        }
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Actor account must be active to modify user status");
        }
        if (actor.getId() != null && actor.getId().equals(target.getId())) {
            throw new AccessDeniedException("Self status modification is not allowed");
        }
        if (!canManage(actor.getRole(), target.getRole())) {
            throw new AccessDeniedException(
                    "Actor role '" + actor.getRole() + "' is not permitted to modify account status of target role '" + target.getRole() + "'"
            );
        }
        if (target.getStatus() == UserStatus.DEACTIVATED) {
            throw new AccessDeniedException("Cannot modify status of a DEACTIVATED account");
        }
        if (target.getRole() == Role.SUPER_ADMIN && target.getStatus() == UserStatus.ACTIVE && newStatus != UserStatus.ACTIVE) {
            long activeSuperAdmins = userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE);
            if (activeSuperAdmins <= 1) {
                throw new AccessDeniedException("Cannot deactivate, suspend or lock the last remaining ACTIVE SUPER_ADMIN");
            }
        }
    }
}
