package com.gamagerecruiters.marketplace.modules.operations.domain;

import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/** The admin area that performed an action. Only SUPPORT_ADMIN and ADMIN may write operations actions. */
public enum OpsActorArea {
    SUPPORT_ADMIN,
    ADMIN;

    /** The writing area for a role, or empty for any role that may not write operations actions. */
    public static Optional<OpsActorArea> fromRole(Role role) {
        if (role == Role.SUPPORT_ADMIN) {
            return Optional.of(SUPPORT_ADMIN);
        }
        if (role == Role.ADMIN) {
            return Optional.of(ADMIN);
        }
        return Optional.empty();
    }
}
